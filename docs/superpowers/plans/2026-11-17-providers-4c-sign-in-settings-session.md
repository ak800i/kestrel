# Providers 4C: Sign-in, Copilot, the Settings App and the Owner's Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner add a profile for every provider through every sign-in method that Kestrel offers (API keys, device codes, browser sign-in with a loopback redirect, imported credentials and server addresses), make Copilot work end to end, test the ChatGPT plan's sign-in and requests against mock servers, show profiles, models, costs, Copilot's allowance and the providers' terms in the settings app, and prepare the session with the owner on kestrel-api37 and the Galaxy A71.

**Architecture:** `:core:providers` gets the credential manager with its refreshers, the sign-in flows (`signin/`), Copilot's discovery, models, headers and targets (`copilot/`), the ChatGPT plan's and xAI's model lists, and the client that sub-project 5 calls (`client/`: `TargetResolver`, `ProfileClient`, `ProvidersRepository`), all plain Kotlin and tested against MockWebServer through plan 4A's `RedirectingCalls`. `:core:privacy` gets `LoopbackServer`, the only new socket code, in the audit's one exception (`network/`). `:app` wires them in `ProviderGraph` at plan 4A's marker in `AppGraph`, runs every sign-in inside the foreground service `SignInService` with a hand-built Custom Tab, adds the page "Providers and profiles" with its add flow, sign-in page and profile page, and the setup checklist's step; its debug build gets the hooks `provider-live` and `provider-standin`, which `scripts\provider-check.ps1` and the session script `scripts\a71-providers.ps1` drive.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.13.0, OkHttp 5.5.1 with `mockwebserver3` (JVM tests; and, new, a `debugImplementation` of `:app` for the stand-in), JUnit 4, `java.security` (RS256, SHA-256), Jetpack Compose with Material 3, AndroidX Test, Pester 6.2.0 and PSScriptAnalyzer 1.25.0 for the scripts.

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

D-numbers refer to the design `.superpowers/sdd/plan-4-design.md`. Code comments call it "design D…", as the code of sub-projects 3 and 4 does.

## Before you start

- Plans 4A and 4B are complete; their last commits are in `.superpowers/sdd/progress.md`. This plan was written against the code as plan 4A leaves it, with plan 4B's names as the design's "Shared names" fix them (`Adapters.forStyle`, `JsonActions`, `Discoveries`, `SigV4Auth`, `ServiceAccountAuth`, `MantleRouting`, `VertexUrls`, `AzureRouting`). The "Changes from the plan" entries of plans 4A and 4B that touch this plan's files must be kept; this plan replaces only the texts that it quotes.
- The counts at the start of this plan are those at the end of plan 4B. The controller gives their values in each task's brief, because later fix commits change them: `:core:providers` JVM R₀, `:app` JVM A₀, `:core:privacy` JVM P₀, the device tests of `:core:privacy` Q₀, the other device tests of `:app` O₀, and Pester T₀ in each shell. The other modules' counts do not change in this plan. Each task gives its counts as these values plus what it adds; before each task, compare the counts with the ones that the earlier tasks left. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file. If a text is missing, stop and ask the controller.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task. Only one Gradle build may run on this computer at a time (process rule 8b); if another agent's build runs, wait for it to end.
- Tasks 4, 6, 7, 8 and 9 use the emulator. Before their device steps, wait for the emulator lock and take it (process rule 8a): run `$deadline = (Get-Date).AddMinutes(60); while (Test-Path .superpowers\sdd\emulator.lock) { if ((Get-Date) -gt $deadline) { throw 'BLOCKED: the emulator lock still exists after 60 minutes' }; Start-Sleep -Seconds 120 }; Set-Content .superpowers\sdd\emulator.lock 'plan 4C task <n>'`, and report BLOCKED to the controller if it throws; delete it with `Remove-Item .superpowers\sdd\emulator.lock` as soon as the device steps are done, also when they fail. Every device command starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. Never send a command to a connected phone.
- No step of this plan sends a request to any provider's API. The only outside request is the redirect case of `CustomTabSignInTest` through postman-echo.com, which runs only in Task 9's dry run, and postman-echo.com is not a provider.
- The emulator's Chrome must have finished its first-run screens (prototype P5 prepared it); `CustomTabSignInTest` stops with a clear message when Chrome shows them.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 4A (the module, the catalog, credentials and cost): `:core:providers`' internal format, `ProviderHttp`, the catalog and `catalog/overrides.json`, `CredentialStore`, cost, profiles and `ProfileHosts`, the allowlist job. Plan 4B (the request styles): the eight adapters, `JsonActions`, `Adapters.forStyle`, `Discoveries`, `SigV4Auth`, `ServiceAccountAuth`, `MantleRouting`, `VertexUrls`, `AzureRouting`. This plan reaches plan 4B's adapter classes only through `Adapters.forStyle`.

Sub-project 5: the agent's use of providers (the loop, escalation, the cap of 10 credits per task, re-prompting after an invalid tool call, `RequestMeta.agentStep`, the activity log's record of each call, redaction before a request, and speaking `ProviderTexts.spoken`).

## Changes to the design that this plan makes

- `SignInResult`, `SignInFailed` and `SignInFailure` come in Task 1 (the design lists them with Task 3's flows), because Task 1's refreshers and entries use them. The fixed clients and addresses of the sign-ins are constants in Task 1's `ChatGptPlanAuth`, `XaiAuth`, `MiniMaxAuth` and `MiniMaxRegion` (`signin/SignInHosts.kt`), so that the refreshers do not wait for the flows. The refreshers are `ChatGptPlanRefresher`, `XaiRefresher` and `MiniMaxRefresher` (`signin/Refreshers.kt`), behind `TokenRefresher`, whose `Refresh` outcome `CredentialManager` turns into `RefreshResult`. `SignInRegistry` maps each key to a `SignInKind` and builds the refreshers; `ProviderGraph` builds the flows.
- `CredentialManager` gains `read(id)`, `hold(credential)` and `release(id)` (a credential in memory only, for the add flow's check before the save, D10) and an optional last parameter `refreshers`, for its tests.
- `TargetResolver(catalog, credentials, models, special)` takes the profile's stored model list and a map of `ProviderTargets` by provider ID; Copilot's `CopilotTargets` is one. `ModelEntries` (a profile's model as a catalog row) lives in `TargetResolver.kt`, and so do `OfferedModels` and `ModelDefaults` (Task 5), because the design's file list names no other file in `client/`.
- `ProfileClient(profile, catalog, applied, targets, adapters, localNetwork, spend, limits, clock, pause)` and `ProvidersRepository(stored, applied, newClient)`; `PlanLimits` is the repository's callback for `planLimitReached`. A profile whose allowlist update does not come within 5 s fails with `NotAllowed`.
- `CopilotDiscovery.apiBase(token)`, `CopilotModels(http, base, auth, runtimeHeaders)`, `CopilotHeaders.forRequests(runtime, extra)`, `CopilotAllowance(http, ideHeaders).check(token)` and `CopilotTargets(catalog, credentials, discovery, updateBase)`; `XaiModels` takes the choice's identity headers as an optional third parameter.
- The page "Providers and profiles" reads a `ProviderData` interface (`settings/ProviderData.kt`), which `StoredProviderData` implements over `ProviderGraph` and `ProvidersPageTest` fakes; `SettingsData` gets `providers` and `providerReady` with defaults, so that the existing fakes compile unchanged. `SignInState`, `CheckResult` and `ProviderData` are public, because the public `SettingsData` names `ProviderData`.
- `PageRequest` gains `resumeAdd`, which only says that the request is the return from a browser sign-in: the Providers page keeps its add flow by itself, because its sub-page is its own saved state, and `MainActivityTest` guards that rule. `SignInService` names the page by `ProviderGraph.PROVIDERS_PAGE` (`"PROVIDERS"`), because Task 6 runs before Task 7 adds `SettingsPage.PROVIDERS`.
- `ACCESS_LOCAL_NETWORK` is in `MainActivity.ASKABLE` only from Android 17 on: Android 13 to 16 do not know the permission and refuse it without a dialog, and `MainActivity` would then open the app's settings page.
- In stand-in mode the hook skips the allowance call (D20) and answers `outcome=skipped`, which `provider-check.ps1` prints as `skipped` and counts as passed. `provider-check.ps1` waits up to 4 minutes per case, not D20's 3, because a tool case's two calls with their retries can take about 188 s; each case may use 1,024 output tokens.
- A credential that the add flow holds in memory is never refreshed (D10), so a 401 during "Check" fails as an expired sign-in. `ProviderGraph`'s other additions: `signInAgain(profileId)` (a GHE.com tenant's profile signs in again on its token's tenant), `timeOutSignIn()` and `reopenPage()`; `CopilotTargets.forget(profileId)`; `SignInFailure.TIMED_OUT`, for `SignInService.onTimeout` on Android 15 and later; `ProviderTexts.FAILED`. A browser flow that opens no loopback port ends as `EXCHANGE_FAILED`, not `NO_CALLBACK`.
- From plan 4B's fix round: the ChatGPT plan's refresher reports a 429 of `auth.openai.com` as `ServerError(503)`; Vertex's "Check" calls `refresh()`, which always exchanges the key; `TargetResolver` checks the Vertex project with `VertexUrls.validProject`.
- `CustomTabSignInTest` checks that `SignInService` runs in the foreground (`dumpsys activity services`), not Kestrel's importance as D10 says, because Android holds an instrumented process at the foreground-service level itself.
- The session takes 44 minutes by its steps' estimates, about 45 with the owner's answers, not D20's 45 to 55.
- The test picture of the image case is drawn at run time by `ProviderHooks.picture()` (the word ORANGE in black on white, as a PNG), instead of a file in `app/src/debug/assets/`, so that the plan needs no binary file.
- `provider-standin` without `-On` answers whether the stand-in runs and whether the default profile is a Copilot profile (`standin=off profile=copilot`), so that the session script checks a sign-in on the device without a hook of its own.
- The emulator's Copilot profile is deleted by the owner on the profile page at the session's end (step `end`), not by a hook, so that no hook deletes profiles.
- The ChatGPT plan's unconfirmed sign-out links to `https://chatgpt.com/settings/usage`: OpenAI's "Accounts and sessions" says "The user can disconnect the app in ChatGPT Settings" without its own address, and names that page for managing each app's access.
- OpenAI's "On your website" page shows its ID-token check with the `jose` library and OpenAI's key set and names no algorithm, so Kestrel keeps D9's RS256-only rule.
- With plan 4B: `TargetResolver` chooses Azure's key header by the same wire as `AzureAdapter` (`StyleOptions.wire`, else `AzureRouting.styleFor`); Vertex's `StyleOptions.wire` is the row's style or null, so that the Vertex adapter decides by the model ID; Copilot's Chat Completions flags go through `StyleOptions.compat`.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `ProviderHttp` and every flow, refresher and discovery | Any coroutine | `ProviderHttp` runs OkHttp on `Dispatchers.IO` itself |
| `CredentialManager`'s refreshes | `AppGraph.scope` (`SupervisorJob`, `Dispatchers.IO`) | each in `scope.async`, behind a mutex per credential, so that a cancelled caller cannot lose a rotated token |
| `LoopbackServer.Session.await` | a blocking call on `Dispatchers.IO` | `LoopbackListenerAdapter` closes the session when its coroutine is cancelled |
| `ProviderGraph`'s sign-ins | `AppGraph.scope` | the page and `SignInService` read `signIn` (a `StateFlow`) |
| `SignInService.watch` | `Dispatchers.Main` in `AppGraph.scope` | stops the service and starts `MainActivity` |
| The Providers page | Main | `rememberCoroutineScope` for the data layer's suspending calls |
| `provider-live` | `AppGraph.scope` | the hook answers at once; `-Result` reads the outcome under a lock |

## File structure

| File | Responsibility |
|---|---|
| `core/providers/src/main/kotlin/dev/kestrel/providers/signin/SignIn.kt`, `SignInHttp.kt`, `SignInHosts.kt`, `TokenTimes.kt`, `Refreshers.kt`, `SignInRegistry.kt`, `Entries.kt`, `credentials/CredentialManager.kt` | Task 1: credentials in use, the refreshers, the entries without a flow (D9, D15) |
| `core/providers/src/main/kotlin/dev/kestrel/providers/client/LocalNetworkAccess.kt`, `TargetResolver.kt`, `ProfileClient.kt`, `ProvidersRepository.kt` | Task 2: targets and the client (D4, D11, D13, D14, D17); Task 5 adds `OfferedModels` |
| `core/providers/src/main/kotlin/dev/kestrel/providers/signin/DeviceCode.kt`, `Pkce.kt`, `GitHubDeviceFlow.kt`, `XaiDeviceFlow.kt`, `MiniMaxDeviceFlow.kt` | Task 3: device codes (D9) |
| `core/privacy/src/main/kotlin/dev/kestrel/privacy/network/LoopbackServer.kt`; `core/providers/src/main/kotlin/dev/kestrel/providers/signin/Loopback.kt`, `IdTokens.kt`, `ChatGptPlanFlow.kt`, `OpenRouterBrowserFlow.kt` | Task 4: browser sign-in (D9, D10) |
| `core/providers/src/main/kotlin/dev/kestrel/providers/copilot/*.kt`, `discovery/ChatGptPlanModels.kt`, `discovery/XaiModels.kt` | Task 5: Copilot and the account lists (D12, D13) |
| `app/src/main/kotlin/dev/kestrel/app/providers/ProviderGraph.kt`, `SignInService.kt`, `CustomTabs.kt`, `AndroidLocalNetworkAccess.kt`, `LoopbackListenerAdapter.kt`; `AppGraph.kt`, `MainActivity.kt`, `AndroidManifest.xml` | Task 6: the Android side of sign-in (D10, D11) |
| `app/src/main/kotlin/dev/kestrel/app/providers/ProviderTexts.kt`, `settings/ProviderData.kt`, `settings/ProvidersPage.kt`, `SettingsApp.kt`, `SetupChecklist.kt`, `ChecklistPage.kt`, `SettingsData.kt`, `res/values/strings.xml` | Task 7: the settings (D17, D18) |
| `app/src/debug/kotlin/dev/kestrel/app/debug/ProviderHooks.kt`, `ProviderStandIn.kt`, `TestHooks.kt`; `app/build.gradle.kts`; `scripts/hook.ps1`, `scripts/provider-check.ps1`, `scripts/tests/ProviderCheck.Tests.ps1`, `AGENTS.md` | Task 8: the live tests (D20, D22) |
| `scripts/a71-providers.ps1`, `scripts/tests/A71Providers.Tests.ps1`, `README.md` | Task 9: the session (D20) |
| `docs/superpowers/specs/2026-11-12-kestrel-design.md` | D23's 4C part, in the task that changes the behaviour: 10.5 and B27 (Task 5); 7.4, 10.4, 13.6 and 16.3 (Task 6); 13.1, 14.2 and 15 (Task 7); 18.4, 19, 19.1 and 20 (Task 9) |

Tests: `core/providers/src/test/kotlin/dev/kestrel/providers/credentials/CredentialManagerTest.kt`, `signin/RefreshersTest.kt`, `TokenTimesTest.kt`, `SignInRegistryTest.kt`, `EntriesTest.kt` and the helper `signin/SignInFixtures.kt` (Task 1); `client/ProfileClientTest.kt`, `TargetResolverTest.kt`, `LocalNetworkTest.kt` (Task 2); `signin/GitHubDeviceFlowTest.kt`, `XaiDeviceFlowTest.kt`, `MiniMaxDeviceFlowTest.kt`, `PkceTest.kt` (Task 3); `signin/IdTokensTest.kt`, `ChatGptPlanFlowTest.kt`, `OpenRouterBrowserFlowTest.kt` with the helpers `TestIdTokens.kt` and `FakeLoopback.kt`, and the device test `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/LoopbackServerTest.kt` (Task 4); `copilot/CopilotDomainTest.kt`, `CopilotDiscoveryTest.kt`, `CopilotAllowanceTest.kt`, `CopilotModelsTest.kt`, `CopilotHeadersTest.kt`, `CopilotTargetsTest.kt`, `discovery/ChatGptPlanModelsTest.kt`, `XaiModelsTest.kt`, `client/OfferedModelsTest.kt` (Task 5); the device tests `app/src/androidTest/kotlin/dev/kestrel/app/providers/ProviderEndToEndTest.kt` and `CustomTabSignInTest.kt` (Task 6); `app/src/androidTest/kotlin/dev/kestrel/app/settings/ProvidersPageTest.kt` and cases in `SettingsAppTest`, `MainActivityTest` and the JVM test `SetupChecklistTest` (Task 7); `scripts/tests/ProviderCheck.Tests.ps1` (Task 8); `scripts/tests/A71Providers.Tests.ps1` (Task 9). Fixtures: `core/providers/src/test/resources/fixtures/` in the folders `github`, `xai`, `minimax`, `chatgpt-plan` and `copilot`.

---

### Task 1: Credentials in use, the refreshers and the entries

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/signin/SignIn.kt`, `SignInHttp.kt`, `SignInHosts.kt`, `TokenTimes.kt`, `Refreshers.kt`, `SignInRegistry.kt`, `Entries.kt`, and `core/providers/src/main/kotlin/dev/kestrel/providers/credentials/CredentialManager.kt`
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/signin/SignInFixtures.kt` (helper), `RefreshersTest.kt`, `TokenTimesTest.kt`, `SignInRegistryTest.kt`, `EntriesTest.kt`, `core/providers/src/test/kotlin/dev/kestrel/providers/credentials/CredentialManagerTest.kt`; fixtures `core/providers/src/test/resources/fixtures/chatgpt-plan/refresh.json`, `xai/refresh.json`, `minimax/refresh.json`

**Interfaces:**
- Consumes: plan 4A's `ProviderHttp.exchange`, `HttpRequest`, `HttpReply`, `ProviderError`, `ProviderException`, `RequestAuth`, `RefreshResult`, `ApiKeyAuth`, `NoAuth`, `KeyHeader`, `Credential` and its variants, `CredentialVault`, `ApiKeys.acceptable`, `Profile`, `ChatGptRegistration`, `Catalog`, `ProviderEntry`, `ChoiceOverride`, `AuthMethod`, `Discovery`, `Hosts.isLocal`, `RedirectingCalls`; plan 4B's `SigV4Auth(keys, region, service, clock)`, `ServiceAccountAuth(key, http, clock)` with its default fourth parameter `tokens = ServiceAccountTokens.SHARED` (the process-wide token cache, on which the Vertex test depends), and `VertexUrls.validProject` (the key-file import).
- Produces (package `dev.kestrel.providers.signin`): `data class SignInResult(val credential: Credential, val baseUrl: String? = null, val hosts: Set<String> = emptySet(), val registration: ChatGptRegistration? = null)`; `enum class SignInFailure { CANCELLED, REFUSED, EXPIRED, NO_CALLBACK, UNTRUSTED_ADDRESS, REGISTRATION_INCOMPLETE, REGISTRATION_REFUSED, PLAN_USE_NOT_ALLOWED, OTHER_ACCOUNT, EXCHANGE_FAILED, TIMED_OUT }`; `class SignInFailed(val kind: SignInFailure, val registration: ChatGptRegistration? = null) : Exception`; `object ChatGptPlanAuth` (the constants `ISSUER`, `HOST`, `DISCOVERY_URL`, `AUTHORIZE_URL`, `TOKEN_URL`, `RESOURCE`, `API_HOST`, `SCOPE`, `PLAN_SCOPE`, `DYNAMIC_CLIENT`, `AGENT_NAME`, `CALLBACK_PATH`, `PREFERRED_PORT`); `object XaiAuth` (`CLIENT_ID`, `SCOPE`, `DISCOVERY_URL`, `DOMAIN`); `object MiniMaxAuth` (`CLIENT_ID`, `SCOPE`, `fun failed(body: JsonObject): Boolean`); `enum class MiniMaxRegion(val accountHost: String, val apiBase: String) { GLOBAL, CHINA }` with `val tokenUrl: String`; `object TokenTimes { fun earliestRefreshAt(value: JsonElement?): Long?; fun miniMaxTime(value: Long, now: Long): Long; fun jwtExpiry(token: String): Long?; fun jwtClaims(token: String): JsonObject? }`; `sealed interface Refresh { data class Done(val token: Credential.OAuthToken); data class ForNow(val error: ProviderError); data class Refused(val writeBack: Credential.OAuthToken? = null) }`; `fun interface TokenRefresher { suspend fun refresh(token: Credential.OAuthToken): Refresh }`; `class ChatGptPlanRefresher(http, clock)`, `class XaiRefresher(http, clock, sleep)`, `class MiniMaxRefresher(http, clock, region: MiniMaxRegion? = null)`; `enum class SignInKind { GITHUB, GITHUB_TENANT, XAI, MINIMAX_GLOBAL, MINIMAX_CHINA, CHATGPT_PLAN, OPENROUTER }`; `object SignInRegistry { val flows: Map<Pair<String, String>, SignInKind>; fun kind(providerId: String, choiceId: String): SignInKind?; fun refresher(providerId: String, choiceId: String, http: ProviderHttp, clock: () -> Long = …): TokenRefresher? }`; `enum class CheckMethod { DISCOVERY, TOKEN_EXCHANGE, SHORT_REQUEST }`; `object Entries { fun apiKey(pasted: String): Credential.ApiKey?; fun customBaseUrl(text: String): HttpUrl?; fun serverAddress(text: String): HttpUrl?; fun unencrypted(url: HttpUrl): Boolean; fun awsKeys(accessKeyId: String, secretAccessKey: String, sessionToken: String?): Credential.AwsAccessKeys?; fun serviceAccount(text: String): Credential.ServiceAccountKey?; fun checkMethod(provider: ProviderEntry, choice: ChoiceOverride?): CheckMethod; fun checkRequest(): ChatRequest; fun masked(key: String): String }`; internal `SignInHttp` (`get`, `form`, `json`, `parse`, `failure`, `temporary`) and the JSON helpers `string`, `number`, `obj`, `hostIn`. Package `dev.kestrel.providers.credentials`: `class CredentialManager(vault: CredentialVault, http: ProviderHttp, catalog: Catalog, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, refreshers: (Profile) -> TokenRefresher? = …)` with `suspend fun read(id: String): Credential?`, `suspend fun store(credential: Credential, id: String? = null): String`, `suspend fun delete(id: String)`, `fun hold(credential: Credential): String`, `fun release(id: String)` and `suspend fun auth(profile: Profile, keyHeader: KeyHeader): RequestAuth`. Test helper (package `dev.kestrel.providers.signin`, test sources): `internal object SignInFixtures { val catalog: Catalog; fun read(path: String): String; fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse; fun fixture(code: Int, path: String): MockResponse }`.

Design D9, D15 and D22. `CredentialManager` keeps each decrypted credential in memory, gives each profile its `RequestAuth`, and refreshes OAuth tokens: before a request when the token expires within 60 s (for the ChatGPT plan not before its `earliest_refresh_at`, which `TokenTimes` reads leniently), at once when it has expired, and after a 401. A refresh runs in the `CoroutineScope` that `:app` gives (with a `SupervisorJob`), behind a mutex per credential, with its own limit of 20 s; a caller that waited on the mutex finds the newer token and does not refresh again, and the rotated token is written to the vault before the mutex is released, so a cancelled caller cannot lose it. The refreshers turn the providers' answers into D9's outcomes: no network, a timeout, a 429 or a 5xx keep the sign-in (`ForNow`), the provider's refusals end it (`Refused`), and the ChatGPT plan's `invalid_client` clears the issued client ID. `SignInRegistry` keys the sign-ins by the catalog's provider and choice IDs, and its test checks them against the synced catalog. `Entries` holds D9's and D11's rules for what the owner types: a key of printable ASCII without spaces, an HTTPS custom base URL, plain HTTP only to a local server, Google's key file (its fields as Google's page "Create and delete service account keys" shows them) and AWS keys; and how the add flow checks each provider. Review fixes: a held credential (the add flow's, in memory only) is never refreshed, so nothing is written under its ID and its refresh token stays unused for the profile that the owner saves; `store` and `delete` wait for a running refresh of the same credential, and a refresh writes the token to memory before the vault; after an early refresh failed for a moment, the next early one waits at least 5 s or the `Retry-After`; the ChatGPT plan's refresher reports a 429 of `auth.openai.com` as `ServerError(503)`, because only a request reports the plan's usage limit; and MiniMax's refresher refuses a token of the other region. The Vertex test checks that two requests with one service-account key make one token exchange, which plan 4B's shared `ServiceAccountTokens` cache gives.

- [ ] **Step 1: Write the test helper, the fixtures and the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/SignInFixtures.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import mockwebserver3.MockResponse
import java.io.File

/** The fixtures of plan 4C's sign-in, Copilot and client tests, and the repository's catalog. */
internal object SignInFixtures {
    /** The merged catalog of the repository's `catalog` folder, which the build passes as `-Dkestrel.catalog`. */
    val catalog: Catalog by lazy {
        File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog")).let {
            CatalogLoader.load(it.resolve("providers.json").readText(), it.resolve("overrides.json").readText())
        }
    }

    /** The text of `src/test/resources/fixtures/<path>`. */
    fun read(path: String): String =
        SignInFixtures::class.java.getResource("/fixtures/$path")?.readText() ?: error("There is no fixture $path")

    /** A JSON reply with [code] and [body], and the headers [headers]. */
    fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
            .apply { headers.forEach { addHeader(it.first, it.second) } }.build()

    /** The fixture at [path] as a JSON reply with [code]. */
    fun fixture(code: Int, path: String): MockResponse = json(code, read(path))
}
```

Create `core/providers/src/test/resources/fixtures/chatgpt-plan/refresh.json`:

```json
{
  "access_token": "test-access-2",
  "refresh_token": "test-refresh-2",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
  "earliest_refresh_at": 1800000000
}
```

Create `core/providers/src/test/resources/fixtures/xai/refresh.json`:

```json
{
  "access_token": "test-xai-access-2",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Create `core/providers/src/test/resources/fixtures/minimax/refresh.json`:

```json
{
  "status": "success",
  "access_token": "test-minimax-access-2",
  "expired_in": 7200,
  "base_resp": { "status_code": 0, "status_msg": "success" }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/RefreshersTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The refreshers of the ChatGPT plan, xAI and MiniMax against MockWebServer (design D9 and D19). */
class RefreshersTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val now = 1_000_000_000_000L

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private val plan = Credential.OAuthToken("test-access-1", "test-refresh-1", now + 10_000, clientId = "oaiapp_test")
    private val xai = Credential.OAuthToken("test-xai-access-1", "test-xai-refresh-1", now, tokenEndpoint = "https://auth.x.ai/oauth2/token")
    private val miniMax = Credential.OAuthToken("test-minimax-access-1", "test-minimax-refresh-1", now, tokenEndpoint = MiniMaxRegion.GLOBAL.tokenUrl)

    @Test
    fun theChatGptPlanRotatesTheRefreshTokenAndReadsEarliestRefreshAt() = runBlocking {
        server.enqueue(fixture(200, "chatgpt-plan/refresh.json"))

        val result = ChatGptPlanRefresher(http) { now }.refresh(plan)

        val token = (result as Refresh.Done).token
        assertEquals("test-access-2", token.access)
        assertEquals("test-refresh-2", token.refresh)
        assertEquals(now + 3_600_000, token.expiresAtMillis)
        assertEquals(1_800_000_000_000L, token.earliestRefreshAtMillis)
        assertEquals("oaiapp_test", token.clientId)
        assertEquals("https://auth.openai.com/api/accounts/oauth/token", calls.requests.single().toString())
        val form = server.takeRequest().body!!.utf8()
        assertEquals(
            "grant_type=refresh_token&client_id=oaiapp_test&refresh_token=test-refresh-1&resource=https%3A%2F%2Fapi.openai.com%2Fv1",
            form,
        )
    }

    @Test
    fun eachPermanentRefusalOfTheChatGptPlanEndsTheSignInAndKeepsTheClientId() = runBlocking {
        val codes = listOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated",
            "refresh_token_reused",
        )
        for (code in codes) server.enqueue(json(400, """{"error":"$code"}"""))

        for (code in codes) assertEquals(code, Refresh.Refused(), ChatGptPlanRefresher(http) { now }.refresh(plan))
    }

    @Test
    fun invalidClientClearsTheChatGptPlansClientId() = runBlocking {
        server.enqueue(json(401, """{"error":"invalid_client"}"""))

        val result = ChatGptPlanRefresher(http) { now }.refresh(plan)

        assertEquals(Refresh.Refused(plan.copy(clientId = null)), result)
    }

    @Test
    fun aRateLimitOrAServerErrorKeepsTheSignIn() = runBlocking {
        server.enqueue(json(429, """{"error":"slow_down"}""", "Retry-After" to "7"))
        server.enqueue(json(503, "{}"))

        // auth.openai.com's 429 is not the plan's usage limit.
        assertEquals(Refresh.ForNow(ProviderError.ServerError(503)), ChatGptPlanRefresher(http) { now }.refresh(plan))
        assertEquals(Refresh.ForNow(ProviderError.ServerError(503)), ChatGptPlanRefresher(http) { now }.refresh(plan))
    }

    @Test
    fun xaiTriesACloudflareChallengeThreeTimesAndKeepsTheOldRefreshToken() = runBlocking {
        repeat(3) { server.enqueue(json(403, "{}", "cf-mitigated" to "challenge")) }
        server.enqueue(fixture(200, "xai/refresh.json"))
        val pauses = mutableListOf<Long>()

        val result = XaiRefresher(http, { now }) { pauses += it }.refresh(xai)

        val token = (result as Refresh.Done).token
        assertEquals("test-xai-access-2", token.access)
        assertEquals("test-xai-refresh-1", token.refresh)
        assertEquals(now + 3_600_000, token.expiresAtMillis)
        assertEquals(listOf(250L, 250L, 250L), pauses)
        assertEquals(4, calls.requests.size)
        repeat(3) { server.takeRequest() }
        assertEquals(
            "grant_type=refresh_token&client_id=b1a00492-073a-47ea-816f-4c329264a828&refresh_token=test-xai-refresh-1",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun xaisFinalRefusalsEndTheSignInAndATokenEndpointOffXaiIsNeverCalled() = runBlocking {
        for (code in listOf("invalid_request", "access_denied", "invalid_grant")) server.enqueue(json(400, """{"error":"$code"}"""))

        repeat(3) { assertEquals(Refresh.Refused(), XaiRefresher(http, { now }) {}.refresh(xai)) }
        assertEquals(Refresh.Refused(), XaiRefresher(http, { now }) {}.refresh(xai.copy(tokenEndpoint = "https://auth.example.com/token")))
        assertEquals(3, calls.requests.size)
    }

    @Test
    fun miniMaxReadsItsExpiryAndEndsTheSignInOnAnErrorStatus() = runBlocking {
        server.enqueue(fixture(200, "minimax/refresh.json"))
        server.enqueue(json(200, """{"status":"error","base_resp":{"status_code":1004,"status_msg":"expired"}}"""))
        server.enqueue(json(200, """{"access_token":"x","base_resp":{"status_code":2013}}"""))

        val token = (MiniMaxRefresher(http, { now }, MiniMaxRegion.GLOBAL).refresh(miniMax) as Refresh.Done).token
        assertEquals("test-minimax-access-2", token.access)
        assertEquals("test-minimax-refresh-1", token.refresh)
        assertEquals(now + 7_200_000, token.expiresAtMillis)
        assertEquals(
            "grant_type=refresh_token&client_id=78257093-7e40-4613-99e0-527b14b39113&refresh_token=test-minimax-refresh-1",
            server.takeRequest().body!!.utf8(),
        )
        assertEquals(Refresh.Refused(), MiniMaxRefresher(http, { now }).refresh(miniMax))
        assertEquals(Refresh.Refused(), MiniMaxRefresher(http, { now }).refresh(miniMax))
        // A global token on a profile of MiniMax's China region is refused without a request.
        assertEquals(Refresh.Refused(), MiniMaxRefresher(http, { now }, MiniMaxRegion.CHINA).refresh(miniMax))
        assertEquals(3, calls.requests.size)
        assertTrue(calls.requests.all { it.toString() == "https://account.minimax.io/oauth2/token" })
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/TokenTimesTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class TokenTimesTest {
    @Test
    fun earliestRefreshAtIsReadAsSecondsMillisecondsOrAnIsoTime() {
        assertEquals(1_800_000_000_000L, TokenTimes.earliestRefreshAt(JsonPrimitive(1_800_000_000)))
        assertEquals(1_800_000_000_123L, TokenTimes.earliestRefreshAt(JsonPrimitive(1_800_000_000_123L)))
        assertEquals(1_800_000_000_000L, TokenTimes.earliestRefreshAt(JsonPrimitive("2027-01-15T08:00:00Z")))
        assertEquals(1_800_000_000_000L, TokenTimes.earliestRefreshAt(JsonPrimitive("2027-01-15T09:00:00+01:00")))
        assertNull(TokenTimes.earliestRefreshAt(JsonPrimitive("soon")))
        assertNull(TokenTimes.earliestRefreshAt(JsonArray(emptyList())))
        assertNull(TokenTimes.earliestRefreshAt(JsonNull))
        assertNull(TokenTimes.earliestRefreshAt(null))
    }

    @Test
    fun miniMaxsExpiryIsRelativeSecondsEpochSecondsOrEpochMilliseconds() {
        val now = 1_700_000_000_000L

        assertEquals(now + 7_200_000, TokenTimes.miniMaxTime(7_200, now))
        assertEquals(1_800_000_000_000L, TokenTimes.miniMaxTime(1_800_000_000, now))
        assertEquals(1_800_000_000_123L, TokenTimes.miniMaxTime(1_800_000_000_123L, now))
    }

    @Test
    fun aJwtsExpiryComesFromItsExpClaim() {
        fun jwt(claims: String) = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.encodeToByteArray()) + ".c2ln"

        assertEquals(1_800_000_000_000L, TokenTimes.jwtExpiry(jwt("""{"exp":1800000000}""")))
        assertNull(TokenTimes.jwtExpiry(jwt("""{"sub":"a"}""")))
        assertNull(TokenTimes.jwtExpiry("not-a-jwt"))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/SignInRegistryTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.signin.SignInFixtures.catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flows' keys are the catalog's choice IDs (design D9). */
class SignInRegistryTest {
    @Test
    fun everyFlowsChoiceExistsInTheCatalogWithItsMethod() {
        for ((key, kind) in SignInRegistry.flows) {
            val choice = catalog.choice(key.first, key.second)?.settings
            val expected = if (kind == SignInKind.CHATGPT_PLAN || kind == SignInKind.OPENROUTER) AuthMethod.BROWSER else AuthMethod.DEVICE_CODE
            assertEquals("$key", expected, choice?.method)
            assertTrue("$key", choice!!.offered)
        }
    }

    @Test
    fun everyOfferedDeviceCodeOrBrowserChoiceHasAFlow() {
        val signIns = catalog.providers.flatMap { provider ->
            provider.authChoices.mapNotNull { it.settings }
                .filter { it.offered && (it.method == AuthMethod.DEVICE_CODE || it.method == AuthMethod.BROWSER) }
                .map { provider.id to it.choiceId }
        }

        assertEquals(SignInRegistry.flows.keys, signIns.toSet())
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/EntriesTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.signin.SignInFixtures.catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntriesTest {
    @Test
    fun aPastedKeyIsTrimmedAndRefusedWithACharacterThatOkHttpCannotSend() {
        assertEquals(Credential.ApiKey("sk-test-a1b2"), Entries.apiKey("  sk-test-a1b2\n"))
        assertNull(Entries.apiKey("sk-test a1b2"))
        assertNull(Entries.apiKey("sk-test-ä1b2"))
        assertNull(Entries.apiKey("sk-test-\u0007"))
        assertNull(Entries.apiKey("   "))
        assertEquals("…a1b2", Entries.masked("sk-test-a1b2"))
    }

    @Test
    fun aCustomBaseUrlMustBeHttpsWithoutUserQueryOrFragment() {
        assertEquals("https://gateway.example.com/v1", Entries.customBaseUrl(" https://gateway.example.com/v1 ").toString())
        assertNull(Entries.customBaseUrl("http://gateway.example.com/v1"))
        assertNull(Entries.customBaseUrl("https://user:secret@gateway.example.com/v1"))
        assertNull(Entries.customBaseUrl("https://gateway.example.com/v1?key=a"))
        assertNull(Entries.customBaseUrl("https://gateway.example.com/v1#top"))
        assertNull(Entries.customBaseUrl("gateway.example.com"))
    }

    @Test
    fun aServerAddressMayUsePlainHttpOnlyOnALocalHost() {
        assertEquals("http://192.168.1.20:11434/", Entries.serverAddress("http://192.168.1.20:11434").toString())
        assertEquals("http://127.0.0.1:1234/v1", Entries.serverAddress("http://127.0.0.1:1234/v1").toString())
        assertEquals("https://litellm.example.com/v1", Entries.serverAddress("https://litellm.example.com/v1").toString())
        assertNull(Entries.serverAddress("http://litellm.example.com/v1"))
        assertNull(Entries.serverAddress("http://192.168.1.20:11434/?token=a"))
        assertEquals(true, Entries.unencrypted(Entries.serverAddress("http://192.168.1.20:11434")!!))
    }

    @Test
    fun aServiceAccountFileKeepsOnlyItsFourFields() {
        val file = """
            {"type":"service_account","project_id":"test-project","private_key_id":"test-key-id",
             "private_key":"-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\n",
             "client_email":"test@test-project.iam.gserviceaccount.com","client_id":"1","token_uri":"https://oauth2.googleapis.com/token"}
        """.trimIndent()

        assertEquals(
            Credential.ServiceAccountKey(
                "test@test-project.iam.gserviceaccount.com",
                "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\n",
                "test-key-id",
                "test-project",
            ),
            Entries.serviceAccount(file),
        )
        assertNull(Entries.serviceAccount(file.replace("service_account", "authorized_user")))
        assertNull(Entries.serviceAccount(file.replace("BEGIN PRIVATE KEY", "BEGIN RSA KEY")))
        assertNull(Entries.serviceAccount("not json"))
        assertNull(Entries.serviceAccount(file.replace("\"project_id\":\"test-project\"", "\"project_id\":\"Test/Project\"")))
    }

    @Test
    fun awsKeysTakeAnOptionalSessionToken() {
        assertEquals(Credential.AwsAccessKeys("AKIATEST", "test-secret", null), Entries.awsKeys(" AKIATEST ", "test-secret", " "))
        assertEquals(Credential.AwsAccessKeys("AKIATEST", "test-secret", "test-session"), Entries.awsKeys("AKIATEST", "test-secret", "test-session"))
        assertNull(Entries.awsKeys("AKIA TEST", "test-secret", null))
    }

    @Test
    fun eachProviderIsCheckedByItsListItsTokenOrOneShortRequest() {
        fun method(provider: String, choice: String) = Entries.checkMethod(catalog.provider(provider)!!, catalog.choice(provider, choice)?.settings)

        assertEquals(CheckMethod.DISCOVERY, method("groq", "groq-api-key"))
        assertEquals(CheckMethod.DISCOVERY, method("ollama", "ollama-server"))
        assertEquals(CheckMethod.DISCOVERY, method("github-copilot", "github-copilot"))
        assertEquals(CheckMethod.TOKEN_EXCHANGE, method("google-vertex", "vertex-service-account"))
        assertEquals(CheckMethod.SHORT_REQUEST, method("microsoft-foundry", "microsoft-foundry-apikey"))
        assertEquals(CheckMethod.SHORT_REQUEST, method("synthetic", "synthetic-api-key"))
        val request = Entries.checkRequest()
        assertEquals(16, request.maxOutputTokens)
        assertEquals(listOf(ChatMessage.User(listOf(Part.Text("Reply with OK.")))), request.messages)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/credentials/CredentialManagerTest.kt`:

```kotlin
package dev.kestrel.providers.credentials

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.signin.Refresh
import dev.kestrel.providers.signin.SignInFixtures
import dev.kestrel.providers.signin.SignInFixtures.catalog
import dev.kestrel.providers.signin.TokenRefresher
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** CredentialManager's cache and its refresh rules (design D9 and D15), with a vault and a refresher in memory. */
class CredentialManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000_000_000L
    private val vault = MemoryVault()
    private val http = ProviderHttp(Call.Factory { _: Request -> error("no request in these tests") })
    private val request = HttpRequest("POST", "https://api.openai.com/v1/responses".toHttpUrl())

    @After
    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    private class MemoryVault : CredentialVault {
        val stored = ConcurrentHashMap<String, Credential>()
        val reads = AtomicInteger()

        override suspend fun read(id: String): Credential? = stored[id].also { reads.incrementAndGet() }

        override suspend fun write(id: String, credential: Credential) {
            stored[id] = credential
        }

        override suspend fun delete(id: String) {
            stored.remove(id)
        }
    }

    /** Gives [outcome] after [gate] completes, and counts its calls. */
    private class TestRefresher(var outcome: (Credential.OAuthToken) -> Refresh, val gate: CompletableDeferred<Unit>? = null) : TokenRefresher {
        val calls = AtomicInteger()

        override suspend fun refresh(token: Credential.OAuthToken): Refresh {
            calls.incrementAndGet()
            gate?.await()
            return outcome(token)
        }
    }

    private fun manager(refresher: TokenRefresher?) = CredentialManager(vault, http, catalog, scope, { now }) { refresher }

    private fun profile(provider: String = "openai", choice: String = "chatgpt-plan", credential: String? = "test-credential") =
        Profile("test-profile", "Test", provider, choice, AuthMethod.BROWSER, credential, lightweight = ProfileModel("a"), versatile = ProfileModel("b"))

    private fun token(access: String = "test-access-1", expiresIn: Long = 3_600_000, earliest: Long? = null) =
        Credential.OAuthToken(access, "test-refresh-1", now + expiresIn, earliest, clientId = "oaiapp_test")

    private fun rotated(token: Credential.OAuthToken) = Refresh.Done(token.copy(access = "test-access-2", refresh = "test-refresh-2", expiresAtMillis = now + 3_600_000))

    private fun errorOf(block: suspend () -> Unit): ProviderError =
        try {
            runBlocking { block() }
            fail("expected a ProviderException")
            throw AssertionError()
        } catch (e: ProviderException) {
            e.error
        }

    @Test
    fun anApiKeyGoesInItsHeaderAndARefusalIsUnauthorized() = runBlocking {
        vault.stored["test-credential"] = Credential.ApiKey("test-key")
        val auth = manager(null).auth(profile("anthropic", "apiKey"), KeyHeader.X_API_KEY)

        assertEquals(mapOf("x-api-key" to "test-key"), auth.headers(request))
        assertEquals(ProviderError.Unauthorized, auth.rejected)
        assertEquals(RefreshResult.NotPossible, auth.refresh())
    }

    @Test
    fun aGitHubTokenIsTheBearerAndARefusalAsksForANewSignIn() = runBlocking {
        vault.stored["test-credential"] = Credential.GitHubToken("test-github-token", "github.com")
        val auth = manager(null).auth(profile("github-copilot", "github-copilot"), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-github-token"), auth.headers(request))
        assertEquals(ProviderError.SignInExpired("github-copilot"), auth.rejected)
        assertEquals(RefreshResult.NotPossible, auth.refresh())
    }

    @Test
    fun aMissingCredentialFailsEveryRequestAsAnExpiredSignIn() {
        val error = errorOf { manager(null).auth(profile(), KeyHeader.BEARER).headers(request) }

        assertEquals(ProviderError.SignInExpired("openai"), error)
    }

    @Test
    fun aTokenThatExpiresWithinAMinuteIsRefreshedBeforeTheRequestAndStored() = runBlocking {
        vault.stored["test-credential"] = token(expiresIn = 30_000)
        val refresher = TestRefresher(::rotated)
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-access-2"), auth.headers(request))
        assertEquals("test-refresh-2", (vault.stored["test-credential"] as Credential.OAuthToken).refresh)
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun theEarlyRefreshWaitsForEarliestRefreshAtButAnExpiredTokenRefreshesAtOnce() = runBlocking {
        vault.stored["test-credential"] = token(expiresIn = 30_000, earliest = now + 45_000)
        val refresher = TestRefresher(::rotated)
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), auth.headers(request))
        assertEquals(0, refresher.calls.get())
        now += 31_000
        // Expired, although earliest_refresh_at is still 14 s away.
        assertEquals(mapOf("Authorization" to "Bearer test-access-2"), auth.headers(request))
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun refreshesOfOneCredentialRunOneAtATimeAndAWaitingCallerUsesTheNewToken() = runBlocking {
        vault.stored["test-credential"] = token()
        val gate = CompletableDeferred<Unit>()
        val refresher = TestRefresher(::rotated, gate)
        val manager = manager(refresher)
        val first = manager.auth(profile(), KeyHeader.BEARER).also { it.headers(request) }
        val second = manager.auth(profile(), KeyHeader.BEARER).also { it.headers(request) }

        val one = async(Dispatchers.Default) { first.refresh() }
        val two = async(Dispatchers.Default) { second.refresh() }
        withTimeout(5_000) { while (refresher.calls.get() == 0) kotlinx.coroutines.delay(10) }
        gate.complete(Unit)

        assertEquals(RefreshResult.Refreshed, one.await())
        assertEquals(RefreshResult.Refreshed, two.await())
        assertEquals(1, refresher.calls.get())
        assertEquals(mapOf("Authorization" to "Bearer test-access-2"), second.headers(request))
    }

    @Test
    fun aCallerCancelledDuringARefreshStillGetsTheRotatedTokenStored() = runBlocking {
        vault.stored["test-credential"] = token()
        val gate = CompletableDeferred<Unit>()
        val refresher = TestRefresher(::rotated, gate)
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER).also { it.headers(request) }

        val caller = launch(Dispatchers.Default) { auth.refresh() }
        withTimeout(5_000) { while (refresher.calls.get() == 0) kotlinx.coroutines.delay(10) }
        caller.cancelAndJoin()
        gate.complete(Unit)

        withTimeout(5_000) { while ((vault.stored["test-credential"] as Credential.OAuthToken).refresh != "test-refresh-2") kotlinx.coroutines.delay(10) }
    }

    @Test
    fun aRefreshThatFailsForNowKeepsTheSignInAndAnExpiredTokenFailsWithThatError() = runBlocking {
        vault.stored["test-credential"] = token(expiresIn = 30_000)
        val refresher = TestRefresher({ Refresh.ForNow(ProviderError.RateLimited(5_000)) })
        val manager = manager(refresher)
        val auth = manager.auth(profile(), KeyHeader.BEARER)

        // Still valid for 30 s, so the request goes with the old token.
        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), auth.headers(request))
        assertEquals(RefreshResult.FailedForNow(ProviderError.RateLimited(5_000)), auth.refresh())
        // The early refresh waits for the Retry-After of its failure, so the next request, also with a new RequestAuth as
        // each call resolves one, does not ask again.
        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), manager.auth(profile(), KeyHeader.BEARER).headers(request))
        assertEquals(2, refresher.calls.get())
        now += 31_000
        assertEquals(ProviderError.RateLimited(5_000), errorOf { auth.headers(request) })
        assertEquals("test-access-1", (vault.stored["test-credential"] as Credential.OAuthToken).access)
    }

    @Test
    fun aRefusedRefreshExpiresTheSignInAndInvalidClientClearsTheClientId() = runBlocking {
        val original = token()
        vault.stored["test-credential"] = original
        val refresher = TestRefresher({ Refresh.Refused(it.copy(clientId = null)) })
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER).also { it.headers(request) }

        assertEquals(RefreshResult.Expired, auth.refresh())
        assertEquals(ProviderError.SignInExpired("openai"), auth.rejected)
        assertNull((vault.stored["test-credential"] as Credential.OAuthToken).clientId)
    }

    @Test
    fun aStoredCredentialIsReadFromTheVaultOnceAndForgottenWhenDeleted() = runBlocking {
        val manager = manager(null)
        val id = manager.store(Credential.ApiKey("test-key"))

        assertEquals(Credential.ApiKey("test-key"), manager.read(id))
        manager.store(Credential.ApiKey("test-key-2"), id)
        assertEquals(Credential.ApiKey("test-key-2"), manager.read(id))
        assertEquals(0, vault.reads.get())
        manager.delete(id)
        assertNull(manager.read(id))
        assertNull(vault.stored[id])
    }

    @Test
    fun aHeldCredentialAuthenticatesWithoutReachingTheVaultUntilItIsReleased() = runBlocking {
        val manager = manager(null)
        val id = manager.hold(Credential.ApiKey("test-key"))

        val auth = manager.auth(profile("anthropic", "apiKey", credential = id), KeyHeader.X_API_KEY)

        assertEquals(mapOf("x-api-key" to "test-key"), auth.headers(request))
        assertEquals(emptyMap<String, Credential>(), vault.stored.toMap())
        manager.release(id)
        assertNull(manager.read(id))
    }

    @Test
    fun aHeldTokenIsNeitherRefreshedNorStored() = runBlocking {
        val refresher = TestRefresher(::rotated)
        val manager = manager(refresher)
        val id = manager.hold(token(expiresIn = 30_000))
        val auth = manager.auth(profile(credential = id), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), auth.headers(request))
        assertEquals(RefreshResult.NotPossible, auth.refresh())
        assertEquals(0, refresher.calls.get())
        assertEquals(emptyMap<String, Credential>(), vault.stored.toMap())
    }

    @Test
    fun aServiceAccountsTokenIsFetchedOnceForSeveralRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
            // An account of its own, because plan 4B's token cache is shared by the whole process.
            vault.stored["test-credential"] = Credential.ServiceAccountKey("test-manager@test-project.iam.gserviceaccount.com", pem, null, "test-project")
            repeat(2) { server.enqueue(SignInFixtures.json(200, """{"access_token":"test-vertex-token","expires_in":3600,"token_type":"Bearer"}""")) }
            val manager = CredentialManager(vault, ProviderHttp(RedirectingCalls(server)), catalog, scope, { now }) { null }

            repeat(2) { manager.auth(profile("google-vertex", "vertex-service-account"), KeyHeader.BEARER).headers(request) }

            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'CredentialManager'", "Unresolved reference 'ChatGptPlanRefresher'", "Unresolved reference 'TokenTimes'", "Unresolved reference 'SignInRegistry'" and "Unresolved reference 'Entries'".

- [ ] **Step 3: Write the sign-in results, the fixed addresses and the token times**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/SignIn.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.profiles.ChatGptRegistration

/**
 * What a sign-in gives (design D9): the credential, the base URL when the flow found one (MiniMax's `resource_url`), the
 * hosts that the flow checked and that the profile needs later, which go into its discovered hosts (design D16), and the
 * ChatGPT plan's registration. It holds a credential, so toString prints only kinds and counts.
 */
data class SignInResult(
    val credential: Credential,
    val baseUrl: String? = null,
    val hosts: Set<String> = emptySet(),
    val registration: ChatGptRegistration? = null,
) {
    override fun toString(): String =
        "SignInResult(credential=$credential, baseUrl=${if (baseUrl == null) "none" else "set"}, hosts=${hosts.size}, " +
            "registration=$registration)"
}

/** Why a sign-in ended without a credential, in the words that its page explains (design D9 and D18). */
enum class SignInFailure {
    CANCELLED,
    REFUSED,
    EXPIRED,
    NO_CALLBACK,
    UNTRUSTED_ADDRESS,
    REGISTRATION_INCOMPLETE,
    REGISTRATION_REFUSED,
    PLAN_USE_NOT_ALLOWED,
    OTHER_ACCOUNT,
    EXCHANGE_FAILED,

    /** Android ended the sign-in's foreground service after its daily time (Android 15 and later). */
    TIMED_OUT,
}

/**
 * A sign-in that ended for a reason that its page explains. Transport and HTTP failures stay ProviderException. A
 * [registration] carries the ChatGPT plan's issued client ID, which the pending sign-in keeps for a retry (design D9).
 */
class SignInFailed(val kind: SignInFailure, val registration: ChatGptRegistration? = null) : Exception("sign-in failed") {
    override fun toString(): String = "SignInFailed(kind=$kind, registration=$registration)"
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/SignInHttp.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.http.HttpReply
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okio.Buffer

/** The requests and answers of the sign-in flows and refreshers, which read OAuth's answers through ProviderHttp.exchange. */
internal object SignInHttp {
    private val json = Json { isLenient = true }

    private val ACCEPT_JSON = mapOf("Accept" to "application/json")

    fun get(url: HttpUrl, headers: Map<String, String> = ACCEPT_JSON): HttpRequest = HttpRequest("GET", url, headers)

    /** A form POST (`application/x-www-form-urlencoded`), as OAuth's token and device endpoints take it. */
    fun form(url: HttpUrl, vararg fields: Pair<String, String>, headers: Map<String, String> = ACCEPT_JSON): HttpRequest {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        val bytes = Buffer().also { body.writeTo(it) }.readByteArray()
        return HttpRequest("POST", url, headers, bytes, "application/x-www-form-urlencoded")
    }

    fun json(url: HttpUrl, body: JsonObject): HttpRequest =
        HttpRequest("POST", url, ACCEPT_JSON, body.toString().encodeToByteArray(), "application/json")

    /** The reply's body as a JSON object, or null when it is not one. */
    fun parse(reply: HttpReply): JsonObject? =
        try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }

    /** The error of a reply that a flow cannot use: a rate limit, a server error, or else a bad request. */
    fun failure(reply: HttpReply): ProviderException = ProviderException(
        when {
            reply.status == 429 -> ProviderError.RateLimited(reply.headers["Retry-After"]?.trim()?.toLongOrNull()?.times(1000))
            reply.status in 500..599 -> ProviderError.ServerError(reply.status)
            else -> ProviderError.BadRequest(reply.status, null)
        },
    )

    /** Whether a status means "try again later": a rate limit or a server error. */
    fun temporary(status: Int): Boolean = status == 429 || status in 500..599
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

/** Whether [host] is [domain] or one of its subdomains. */
internal fun hostIn(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/SignInHosts.kt`:

```kotlin
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
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/TokenTimes.kt`:

```kotlin
package dev.kestrel.providers.signin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

/** The times that the providers' token answers give in more than one form (design D9). */
object TokenTimes {
    private const val EPOCH_MILLIS_FROM = 1_000_000_000_000L
    private const val EPOCH_SECONDS_FROM = 1_000_000_000L

    /**
     * The ChatGPT plan's `earliest_refresh_at`, which OpenAI's pages give no format: a number below 10^12 is epoch
     * seconds, a larger one epoch milliseconds, a string an ISO-8601 time; any other value is null. It only delays the
     * early refresh, so a value that Kestrel cannot read never fails a sign-in or a refresh.
     */
    fun earliestRefreshAt(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) {
            return try {
                OffsetDateTime.parse(primitive.content).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                try {
                    Instant.parse(primitive.content).toEpochMilli()
                } catch (e: DateTimeParseException) {
                    null
                }
            }
        }
        val number = primitive.doubleOrNull?.toLong() ?: return null
        if (number <= 0) return null
        return if (number < EPOCH_MILLIS_FROM) number * 1000 else number
    }

    /**
     * MiniMax's `expired_in` (research r31 left the unit open): below 10^9 seconds from [now], from 10^9 to 10^12 epoch
     * seconds, and from 10^12 on epoch milliseconds.
     */
    fun miniMaxTime(value: Long, now: Long): Long = when {
        value < EPOCH_SECONDS_FROM -> now + value * 1000
        value < EPOCH_MILLIS_FROM -> value * 1000
        else -> value
    }

    /** The `exp` claim of a JWT, in epoch milliseconds, without checking its signature; null when there is none. */
    fun jwtExpiry(token: String): Long? =
        (jwtClaims(token)?.get("exp") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toLong()?.times(1000)

    /** The claims of a JWT, without checking its signature; null when [token] is not a JWT. */
    fun jwtClaims(token: String): JsonObject? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
```

- [ ] **Step 4: Write the refreshers, the registry and the entries**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/Refreshers.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpReply
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a refresh of an OAuth token gave (design D9); CredentialManager turns it into a RefreshResult. */
sealed interface Refresh {
    /** The new token, which CredentialManager stores before it releases the credential's lock. */
    data class Done(val token: Credential.OAuthToken) : Refresh

    /** No network, a timeout, a 429 or a 5xx: the sign-in stays as it is. */
    data class ForNow(val error: ProviderError) : Refresh

    /** The provider refused the refresh for good; [writeBack] is the credential to keep instead, if any. */
    data class Refused(val writeBack: Credential.OAuthToken? = null) : Refresh
}

/** Refreshes one provider's OAuth token. */
fun interface TokenRefresher {
    suspend fun refresh(token: Credential.OAuthToken): Refresh
}

/** Sends [request], or returns the transport failure as a [Refresh.ForNow]. */
private suspend fun ProviderHttp.tryExchange(request: HttpRequest): Pair<HttpReply?, Refresh?> =
    try {
        exchange(request) to null
    } catch (e: ProviderException) {
        null to Refresh.ForNow(e.error)
    }

/**
 * The ChatGPT plan's refresh (design D9; OpenAI's "Accounts and sessions"): a form POST with the issued client ID, the
 * refresh token and the resource, without a scope. Each refresh rotates the refresh token. `invalid_client` clears the
 * client ID, so that the next sign-in registers Kestrel again; every other refusal (`invalid_grant`,
 * `invalid_refresh_token`, `token_expired`, `refresh_token_expired`, `refresh_token_invalidated`, `refresh_token_reused`)
 * keeps it for the next sign-in.
 */
class ChatGptPlanRefresher(private val http: ProviderHttp, private val clock: () -> Long = System::currentTimeMillis) : TokenRefresher {
    override suspend fun refresh(token: Credential.OAuthToken): Refresh {
        val clientId = token.clientId ?: return Refresh.Refused()
        val refreshToken = token.refresh ?: return Refresh.Refused()
        val request = SignInHttp.form(
            ChatGptPlanAuth.TOKEN_URL.toHttpUrl(),
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "resource" to ChatGptPlanAuth.RESOURCE,
        )
        val (reply, failed) = http.tryExchange(request)
        if (reply == null) return failed!!
        // A 429 of auth.openai.com is not the plan's usage limit, which only a request reports (design D17), so it is a
        // server error for now.
        if (reply.status == 429) return Refresh.ForNow(ProviderError.ServerError(503))
        if (SignInHttp.temporary(reply.status)) return Refresh.ForNow(SignInHttp.failure(reply).error)
        val body = SignInHttp.parse(reply)
        if (reply.status !in 200..299) {
            return if (body?.string("error") == "invalid_client") Refresh.Refused(token.copy(clientId = null)) else Refresh.Refused()
        }
        val access = body?.string("access_token") ?: return Refresh.ForNow(ProviderError.Malformed)
        val now = clock()
        return Refresh.Done(
            token.copy(
                access = access,
                refresh = body.string("refresh_token") ?: refreshToken,
                expiresAtMillis = body.number("expires_in")?.let { now + it * 1000 },
                earliestRefreshAtMillis = TokenTimes.earliestRefreshAt(body["earliest_refresh_at"]),
            ),
        )
    }
}

/**
 * xAI's refresh (design D9, research r31): a form POST to the token endpoint that the sign-in discovered, which must be
 * HTTPS on `x.ai`. `invalid_request`, `access_denied` and `invalid_grant` are final; a 429 or a 5xx keeps the sign-in,
 * and a 403 with `cf-mitigated: challenge` is tried again up to 3 times, 250 ms apart.
 */
class XaiRefresher(
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : TokenRefresher {
    override suspend fun refresh(token: Credential.OAuthToken): Refresh {
        val endpoint = token.tokenEndpoint?.toHttpUrlOrNull()?.takeIf { it.isHttps && hostIn(it.host, XaiAuth.DOMAIN) }
            ?: return Refresh.Refused()
        val refreshToken = token.refresh ?: return Refresh.Refused()
        val request = SignInHttp.form(
            endpoint,
            "grant_type" to "refresh_token",
            "client_id" to XaiAuth.CLIENT_ID,
            "refresh_token" to refreshToken,
        )
        var challenges = 0
        while (true) {
            val (reply, failed) = http.tryExchange(request)
            if (reply == null) return failed!!
            if (reply.status == 403 && reply.headers["cf-mitigated"].equals("challenge", ignoreCase = true)) {
                if (++challenges > CHALLENGE_RETRIES) return Refresh.ForNow(ProviderError.QuotaOrPolicy(403))
                sleep(CHALLENGE_PAUSE_MILLIS)
                continue
            }
            if (SignInHttp.temporary(reply.status)) return Refresh.ForNow(SignInHttp.failure(reply).error)
            val body = SignInHttp.parse(reply)
            if (reply.status !in 200..299) return Refresh.Refused()
            val access = body?.string("access_token") ?: return Refresh.ForNow(ProviderError.Malformed)
            val expiresAt = body.number("expires_in")?.let { clock() + it * 1000 } ?: TokenTimes.jwtExpiry(access)
            return Refresh.Done(token.copy(access = access, refresh = body.string("refresh_token") ?: refreshToken, expiresAtMillis = expiresAt))
        }
    }

    private companion object {
        const val CHALLENGE_RETRIES = 3
        const val CHALLENGE_PAUSE_MILLIS = 250L
    }
}

/**
 * MiniMax's refresh (design D9, research r31): a form POST to the token endpoint of the account host that signed in, which
 * must be one of MiniMax's two and, when [region] is given, the profile's region's: a token of the other region is
 * refused, so that the owner signs in again. A body with `status: "error"` or a `base_resp.status_code` other than 0 is
 * final; without a new refresh token the old one stays.
 */
class MiniMaxRefresher(
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val region: MiniMaxRegion? = null,
) : TokenRefresher {
    override suspend fun refresh(token: Credential.OAuthToken): Refresh {
        val endpoint = token.tokenEndpoint?.toHttpUrlOrNull()
            ?.takeIf { url -> url.isHttps && MiniMaxRegion.entries.any { it.accountHost == url.host } }
            ?.takeIf { region == null || it.toString() == region.tokenUrl }
            ?: return Refresh.Refused()
        val refreshToken = token.refresh ?: return Refresh.Refused()
        val request = SignInHttp.form(
            endpoint,
            "grant_type" to "refresh_token",
            "client_id" to MiniMaxAuth.CLIENT_ID,
            "refresh_token" to refreshToken,
        )
        val (reply, failed) = http.tryExchange(request)
        if (reply == null) return failed!!
        if (SignInHttp.temporary(reply.status)) return Refresh.ForNow(SignInHttp.failure(reply).error)
        val body = SignInHttp.parse(reply)
        if (reply.status !in 200..299 || body == null || MiniMaxAuth.failed(body)) return Refresh.Refused()
        val access = body.string("access_token") ?: return Refresh.ForNow(ProviderError.Malformed)
        val now = clock()
        return Refresh.Done(
            token.copy(
                access = access,
                refresh = body.string("refresh_token") ?: refreshToken,
                expiresAtMillis = body.number("expired_in")?.let { TokenTimes.miniMaxTime(it, now) },
            ),
        )
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/SignInRegistry.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.http.ProviderHttp

/** The sign-in flows that are tied to one choice (design D9). */
enum class SignInKind { GITHUB, GITHUB_TENANT, XAI, MINIMAX_GLOBAL, MINIMAX_CHINA, CHATGPT_PLAN, OPENROUTER }

/**
 * The flows and refreshers keyed by provider and choice ID (design D9). The IDs are those of the catalog that plan 4A
 * synced; SignInRegistryTest checks that each key exists in it with the method of its flow.
 */
object SignInRegistry {
    val flows: Map<Pair<String, String>, SignInKind> = mapOf(
        ("github-copilot" to "github-copilot") to SignInKind.GITHUB,
        ("github-copilot" to "github-copilot-enterprise") to SignInKind.GITHUB_TENANT,
        ("xai" to "xai-oauth") to SignInKind.XAI,
        ("minimax-portal" to "minimax-global-oauth") to SignInKind.MINIMAX_GLOBAL,
        ("minimax-portal" to "minimax-cn-oauth") to SignInKind.MINIMAX_CHINA,
        ("openai" to "chatgpt-plan") to SignInKind.CHATGPT_PLAN,
        ("openrouter" to "openrouter-oauth") to SignInKind.OPENROUTER,
    )

    fun kind(providerId: String, choiceId: String): SignInKind? = flows[providerId to choiceId]

    /** The refresher of the choice's OAuth tokens, or null for a credential that cannot be refreshed. */
    fun refresher(providerId: String, choiceId: String, http: ProviderHttp, clock: () -> Long = System::currentTimeMillis): TokenRefresher? =
        when (kind(providerId, choiceId)) {
            SignInKind.CHATGPT_PLAN -> ChatGptPlanRefresher(http, clock)
            SignInKind.XAI -> XaiRefresher(http, clock)
            SignInKind.MINIMAX_GLOBAL -> MiniMaxRefresher(http, clock, MiniMaxRegion.GLOBAL)
            SignInKind.MINIMAX_CHINA -> MiniMaxRefresher(http, clock, MiniMaxRegion.CHINA)
            SignInKind.GITHUB, SignInKind.GITHUB_TENANT, SignInKind.OPENROUTER, null -> null
        }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/Entries.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.ChoiceOverride
import dev.kestrel.providers.catalog.Discovery
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.credentials.ApiKeys
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.Hosts
import dev.kestrel.providers.styles.VertexUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How the add flow checks a credential before it saves the profile (design D9). */
enum class CheckMethod {
    /** A model discovery call, where the provider has a list. */
    DISCOVERY,

    /** Vertex's token exchange with the service-account key. */
    TOKEN_EXCHANGE,

    /** One short request ("Reply with OK."), for which the owner chooses or types the model first. */
    SHORT_REQUEST,
}

/**
 * The entries of the add flow that need no sign-in flow (design D9 and D11): a pasted API key, a custom base URL, a local
 * server's address, AWS access keys and a Google service-account key file; and how the flow checks each.
 */
object Entries {
    private val json = Json { isLenient = true }

    /**
     * The pasted key, trimmed, when OkHttp can send it as a header: not empty, and only printable ASCII without spaces.
     * Otherwise null, and the page says "The key has a character that keys do not have. Paste it again."
     */
    fun apiKey(pasted: String): Credential.ApiKey? = pasted.trim().takeIf(ApiKeys::acceptable)?.let { Credential.ApiKey(it) }

    /** A custom base URL of an API-key choice: HTTPS, without user information, a query or a fragment. */
    fun customBaseUrl(text: String): HttpUrl? =
        text.trim().toHttpUrlOrNull()?.takeIf { it.isHttps && plain(it) }

    /**
     * A local server's address (design D11): plain HTTP only to a local host (an IP address of the phone or the owner's
     * network, `localhost` or a `.local` name), HTTPS to any host; without user information, a query or a fragment.
     */
    fun serverAddress(text: String): HttpUrl? =
        text.trim().toHttpUrlOrNull()?.takeIf { plain(it) && (it.isHttps || Hosts.isLocal(it.host)) }

    /** Whether the page warns that the connection to [url] is not encrypted (spec 13.1). */
    fun unencrypted(url: HttpUrl): Boolean = !url.isHttps

    /** AWS access keys for Bedrock, each part sendable in a signed request. */
    fun awsKeys(accessKeyId: String, secretAccessKey: String, sessionToken: String?): Credential.AwsAccessKeys? {
        val id = accessKeyId.trim().takeIf(ApiKeys::acceptable) ?: return null
        val secret = secretAccessKey.trim().takeIf(ApiKeys::acceptable) ?: return null
        val session = sessionToken?.trim()?.takeIf { it.isNotEmpty() }
        if (session != null && !ApiKeys.acceptable(session)) return null
        return Credential.AwsAccessKeys(id, secret, session)
    }

    /**
     * The four fields that Kestrel keeps of a Google service-account key file (`client_email`, `private_key`,
     * `private_key_id` and `project_id`, as Google's page "Create and delete service account keys" shows the file), or
     * null when [text] is not such a file. Kestrel forgets the file itself.
     */
    fun serviceAccount(text: String): Credential.ServiceAccountKey? {
        val file = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        if (file.string("type") != "service_account") return null
        val email = file.string("client_email")?.takeIf { it.isNotBlank() } ?: return null
        val key = file.string("private_key")?.takeIf { "-----BEGIN PRIVATE KEY-----" in it } ?: return null
        // A project ID that is not in Google's form would fail every request (plan 4B's VertexUrls), so the import refuses it.
        val project = file.string("project_id")?.takeIf(VertexUrls::validProject) ?: return null
        return Credential.ServiceAccountKey(email, key, file.string("private_key_id")?.takeIf { it.isNotBlank() }, project)
    }

    /**
     * How the add flow checks a profile of [provider] with [choice] (design D9): Vertex by its token exchange; Azure, and a
     * provider whose catalog says STATIC, by one short request, for which the owner chooses or types the model first;
     * everything else, among them the local servers and the sign-ins with their own lists, by model discovery.
     */
    fun checkMethod(provider: ProviderEntry, choice: ChoiceOverride?): CheckMethod = when {
        provider.style == RequestStyle.VERTEX -> CheckMethod.TOKEN_EXCHANGE
        provider.style == RequestStyle.AZURE -> CheckMethod.SHORT_REQUEST
        choice?.method == AuthMethod.SERVER_ADDRESS -> CheckMethod.DISCOVERY
        SignInRegistry.kind(provider.id, choice?.choiceId.orEmpty()) != null -> CheckMethod.DISCOVERY
        provider.discovery == Discovery.STATIC -> CheckMethod.SHORT_REQUEST
        else -> CheckMethod.DISCOVERY
    }

    /** The short request of a check (design D9): "Reply with OK." with at most 16 output tokens. */
    fun checkRequest(): ChatRequest =
        ChatRequest(system = "", messages = listOf(ChatMessage.User(listOf(Part.Text("Reply with OK.")))), maxOutputTokens = 16)

    /** A key as the pages show it after entry: its last four characters (design D15). */
    fun masked(key: String): String = "…" + key.takeLast(4)

    private fun plain(url: HttpUrl): Boolean =
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null
}
```

- [ ] **Step 5: Write the credential manager**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/credentials/CredentialManager.kt`:

```kotlin
package dev.kestrel.providers.credentials

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.signin.Refresh
import dev.kestrel.providers.signin.SignInRegistry
import dev.kestrel.providers.signin.TokenRefresher
import dev.kestrel.providers.styles.ServiceAccountAuth
import dev.kestrel.providers.styles.SigV4Auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The credentials of the profiles (design D9 and D15): it keeps each decrypted credential in memory for the life of the
 * process, because StrongBox is slow, and gives each profile its [RequestAuth].
 *
 * An OAuth token is refreshed before a request when it expires within 60 s (for the ChatGPT plan not before its
 * `earliest_refresh_at`), at once when it has expired, and when a request fails with 401. Refreshes of one credential run
 * one at a time, and a request that waited for one uses the newer token instead of refreshing again. Each refresh runs in
 * [scope] (a scope with a SupervisorJob that `:app` gives), from the token request to the vault write, with its own limit
 * of 20 s, and every caller awaits it: so a caller's stop or ProfileClient's limit of 30 s ends the caller's wait but not
 * the refresh, and a rotated refresh token is stored before the lock is released, also when the request that caused the
 * refresh is gone.
 */
class CredentialManager(
    private val vault: CredentialVault,
    private val http: ProviderHttp,
    private val catalog: Catalog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshers: (Profile) -> TokenRefresher? = { SignInRegistry.refresher(it.providerId, it.choiceId, http, clock) },
) {
    private val cache = ConcurrentHashMap<String, Credential>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * After an early refresh of a credential failed for a moment, the time before which the next early one waits; kept per
     * credential, because each call resolves a new RequestAuth. An expired token and a 401 refresh at once all the same.
     */
    private val earlyAgainAt = ConcurrentHashMap<String, Long>()

    /** The credential with [id], from memory or from the vault; null when it is missing or could not be read. */
    suspend fun read(id: String): Credential? = cache[id] ?: vault.read(id)?.also { cache[id] = it }

    /**
     * Stores [credential] under [id], or under a new ID, and returns the ID; an existing ID is replaced. It waits for a
     * running refresh of [id], so that the refresh cannot overwrite the new credential with the old one.
     */
    suspend fun store(credential: Credential, id: String? = null): String {
        val key = id ?: "credential-${UUID.randomUUID()}"
        lock(key).withLock {
            write(key, credential)
            earlyAgainAt.remove(key)
        }
        return key
    }

    /** Waits for a running refresh of [id], so that the refresh cannot write the credential back after the delete. */
    suspend fun delete(id: String) {
        lock(id).withLock {
            vault.delete(id)
            cache.remove(id)
            earlyAgainAt.remove(id)
        }
    }

    /**
     * Keeps [credential] in memory only, under a new ID, for the add flow's check before the owner saves the profile
     * (design D10): Kestrel forgets it with [release], or when its process ends. A held credential is never refreshed, so
     * that nothing is written to the vault under its ID and its refresh token stays unused for the profile that the owner
     * saves; a 401 during the check fails as an expired sign-in.
     */
    fun hold(credential: Credential): String = "$PENDING${UUID.randomUUID()}".also { cache[it] = credential }

    fun release(id: String) {
        cache.remove(id)
    }

    /**
     * The authentication of [profile]'s requests, with [keyHeader] for an API key. A missing or unreadable credential
     * fails each request as SignInExpired, so that the owner signs in again; [RequestAuth.rejected] is Unauthorized for
     * an API key and SignInExpired for every other credential (design D1 and D9).
     */
    suspend fun auth(profile: Profile, keyHeader: KeyHeader): RequestAuth {
        val id = profile.credentialId ?: return NoAuth
        val expired = ProviderError.SignInExpired(profile.providerId)
        return when (val credential = read(id)) {
            null -> Fails(expired)
            is Credential.ApiKey -> ApiKeyAuth(credential.key, keyHeader)
            is Credential.GitHubToken -> Bearer(credential.token, expired)
            is Credential.AwsAccessKeys -> profile.region?.let { Rejecting(SigV4Auth(credential, it, clock = clock), expired) } ?: Fails(expired)
            is Credential.ServiceAccountKey -> Rejecting(ServiceAccountAuth(credential, http, clock), expired)
            // A held credential is not saved yet: a refresh would write it to the vault and use up the refresh token that the
            // add flow saves later (design D10).
            is Credential.OAuthToken -> TokenAuth(id, if (id.startsWith(PENDING)) null else refreshers(profile), expired)
        }
    }

    private suspend fun refresh(id: String, used: String, refresher: TokenRefresher): RefreshResult =
        scope.async { refreshNow(id, used, refresher) }.await()

    private suspend fun refreshNow(id: String, used: String, refresher: TokenRefresher): RefreshResult =
        lock(id).withLock {
            val current = read(id) as? Credential.OAuthToken ?: return@withLock RefreshResult.Expired
            // Another request refreshed the token while this one waited.
            if (current.access != used) return@withLock RefreshResult.Refreshed
            val outcome = withTimeoutOrNull(REFRESH_LIMIT_MILLIS) {
                try {
                    refresher.refresh(current)
                } catch (e: ProviderException) {
                    Refresh.ForNow(e.error)
                }
            } ?: Refresh.ForNow(ProviderError.Timeout)
            when (outcome) {
                is Refresh.Done -> {
                    write(id, outcome.token)
                    earlyAgainAt.remove(id)
                    RefreshResult.Refreshed
                }
                is Refresh.ForNow -> RefreshResult.FailedForNow(outcome.error)
                is Refresh.Refused -> {
                    outcome.writeBack?.let { write(id, it) }
                    RefreshResult.Expired
                }
            }
        }

    private fun lock(id: String): Mutex = locks.getOrPut(id) { Mutex() }

    /** Called with the credential's lock held: memory first, so that a failed vault write keeps a rotated token for now. */
    private suspend fun write(id: String, credential: Credential) {
        cache[id] = credential
        vault.write(id, credential)
    }

    /** An OAuth token, refreshed by [refresher] (design D9). */
    private inner class TokenAuth(private val id: String, private val refresher: TokenRefresher?, override val rejected: ProviderError) : RequestAuth {
        @Volatile
        private var lastAccess: String? = null

        override suspend fun headers(request: HttpRequest): Map<String, String> {
            var token = read(id) as? Credential.OAuthToken ?: throw ProviderException(rejected)
            if (refresher != null && due(token)) {
                when (val result = refresh(id, token.access, refresher)) {
                    RefreshResult.Refreshed -> token = read(id) as? Credential.OAuthToken ?: throw ProviderException(rejected)
                    is RefreshResult.FailedForNow -> {
                        if (expired(token)) throw ProviderException(result.error)
                        val wait = (result.error as? ProviderError.RateLimited)?.retryAfterMillis ?: 0L
                        earlyAgainAt[id] = clock() + maxOf(EARLY_RETRY_MILLIS, wait)
                    }
                    RefreshResult.Expired, RefreshResult.NotPossible -> throw ProviderException(rejected)
                }
            }
            lastAccess = token.access
            return mapOf("Authorization" to "Bearer ${token.access}")
        }

        override suspend fun refresh(): RefreshResult {
            val refresher = refresher ?: return RefreshResult.NotPossible
            val used = lastAccess ?: (read(id) as? Credential.OAuthToken)?.access ?: return RefreshResult.Expired
            return refresh(id, used, refresher)
        }

        private fun expired(token: Credential.OAuthToken): Boolean = token.expiresAtMillis?.let { clock() >= it } == true

        private fun due(token: Credential.OAuthToken): Boolean {
            val expiresAt = token.expiresAtMillis ?: return false
            val now = clock()
            if (now >= expiresAt) return true
            return now >= expiresAt - EARLY_MILLIS && now >= (earlyAgainAt[id] ?: 0L) && (token.earliestRefreshAtMillis?.let { now >= it } ?: true)
        }

        override fun toString(): String = "TokenAuth(refreshable=${refresher != null})"
    }

    /** A token that is the bearer of every call and cannot be refreshed, such as Copilot's GitHub token. */
    private class Bearer(private val token: String, override val rejected: ProviderError) : RequestAuth {
        override suspend fun headers(request: HttpRequest): Map<String, String> = mapOf("Authorization" to "Bearer $token")

        override fun toString(): String = "Bearer(length=${token.length})"
    }

    /** Plan 4B's signers, with the rejection of a sign-in rather than of an API key. */
    private class Rejecting(private val inner: RequestAuth, override val rejected: ProviderError) : RequestAuth {
        override suspend fun headers(request: HttpRequest): Map<String, String> = inner.headers(request)

        override suspend fun refresh(): RefreshResult = inner.refresh()

        override fun toString(): String = "Rejecting($inner)"
    }

    /** A credential that is missing or could not be read: every request fails, so that the owner signs in again. */
    private class Fails(override val rejected: ProviderError) : RequestAuth {
        override suspend fun headers(request: HttpRequest): Map<String, String> = throw ProviderException(rejected)

        override fun toString(): String = "Fails($rejected)"
    }

    private companion object {
        const val PENDING = "pending-"
        const val EARLY_MILLIS = 60_000L
        const val EARLY_RETRY_MILLIS = 5_000L
        const val REFRESH_LIMIT_MILLIS = 20_000L
    }
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 31 tests in `:core:providers`: `CredentialManagerTest` 13, `RefreshersTest` 7, `TokenTimesTest` 3, `SignInRegistryTest` 2 and `EntriesTest` 6.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:providers` R₀ + 31; `:app` A₀ and `:core:privacy` P₀ as before), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit, whose report `core\providers\build\reports\kestrel-audit\network-apis.txt` lists no finding: the new code names only OkHttp's `HttpUrl`, `FormBody` and Okio's `Buffer`.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
$main = 'core/providers/src/main/kotlin/dev/kestrel/providers'
$test = 'core/providers/src/test/kotlin/dev/kestrel/providers'
$fix = 'core/providers/src/test/resources/fixtures'
git add "$main/signin/SignIn.kt" "$main/signin/SignInHttp.kt" "$main/signin/SignInHosts.kt" "$main/signin/TokenTimes.kt" "$main/signin/Refreshers.kt" "$main/signin/SignInRegistry.kt" "$main/signin/Entries.kt" "$main/credentials/CredentialManager.kt"
git add "$test/signin/SignInFixtures.kt" "$test/signin/RefreshersTest.kt" "$test/signin/TokenTimesTest.kt" "$test/signin/SignInRegistryTest.kt" "$test/signin/EntriesTest.kt" "$test/credentials/CredentialManagerTest.kt" "$fix/chatgpt-plan/refresh.json" "$fix/xai/refresh.json" "$fix/minimax/refresh.json"
git commit -m "feat(providers): add the credential manager with its refreshers, the sign-in registry and the entries" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Targets and the client

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/client/LocalNetworkAccess.kt`, `TargetResolver.kt`, `ProfileClient.kt`, `ProvidersRepository.kt`
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/client/ProfileClientTest.kt`, `TargetResolverTest.kt`, `LocalNetworkTest.kt`

**Interfaces:**
- Consumes: Task 1's `CredentialManager`, `SignInFixtures`; plan 4A's `ModelTarget`, `StyleOptions`, `RequestHeaders`, `ChatAdapter`, `ChatRequest`, `ChatResponse`, `ProfileHosts.baseUrl` and `hosts`, `ProvidersSetting.usable`, `PriceTable`, `CostMeter`, `Cost`, `Spending.monthOf`, `Hosts.isLocal` and `isLoopback`, `DiscoveredModel`, `CompatFlags`, `ToolMode`, `InputKind`; plan 4B's `JsonActions(inner)`, `MantleRouting.route(modelId, region)`, `VertexUrls.base(location, projectId)`, `validLocation` and `validProject`, `AzureRouting.styleFor(deployment)` and `normalise(endpoint)`.
- Produces (package `dev.kestrel.providers.client`): `fun interface LocalNetworkAccess { fun allowed(host: String): Boolean }`; `object LocalNetwork { const val LOOPBACK_NEEDS_PERMISSION = false; fun needsPermission(host: String): Boolean }`; `fun interface ProviderTargets { suspend fun target(profile: Profile, role: ModelRole, entry: ModelEntry): ModelTarget }`; `class TargetResolver(catalog: Catalog, credentials: CredentialManager, models: suspend (profileId: String) -> List<DiscoveredModel> = { emptyList() }, special: Map<String, ProviderTargets> = emptyMap())` with `suspend fun resolve(profile: Profile, role: ModelRole): ModelTarget` and `companion object { const val MANTLE; const val GROK_PROXY; fun defaultKeyHeader(style: RequestStyle): KeyHeader }`; `object ModelEntries { fun entryFor(provider: ProviderEntry?, modelId: String, discovered: List<DiscoveredModel>): ModelEntry; fun fromDiscovered(found: DiscoveredModel): ModelEntry; fun withOwnerChoices(entry: ModelEntry, chosen: ProfileModel): ModelEntry }`; `data class PricedResponse(val response: ChatResponse, val cost: Cost, val style: RequestStyle)`; `interface PlanLimits { fun reached(profileId: String); fun cleared(profileId: String) }`; `class ProfileClient(val profile: Profile, catalog: Catalog, applied: StateFlow<ProvidersSetting?>, targets: suspend (Profile, ModelRole) -> ModelTarget, adapters: (RequestStyle) -> ChatAdapter, localNetwork: LocalNetworkAccess, spend: suspend (profileId: String, month: String, credits: Double, tokens: Long) -> Unit, limits: PlanLimits, clock: () -> Long = …, pause: suspend (Long) -> Unit = …)` with `suspend fun complete(role: ModelRole, request: ChatRequest): PricedResponse`; `class ProvidersRepository(stored: StateFlow<ProvidersSetting?>, applied: StateFlow<ProvidersSetting?>, newClient: (Profile, PlanLimits) -> ProfileClient)` with `fun defaultClient(): ProfileClient?`, `fun client(profileId: String): ProfileClient?` and `val planLimitReached: StateFlow<Set<String>>`.

Design D4, D7, D11, D13, D14 and D17. `TargetResolver` builds each request's target from the one rule that the allowlist uses too (`ProfileHosts.baseUrl`), so an xAI sign-in goes to the Grok CLI proxy with the choice's identity headers and `x-grok-model-override`, and an xAI API key goes to `api.x.ai` with none of them; the key header comes from the choice, else from the style (`Authorization: Bearer`, `x-api-key` for Anthropic Messages, `x-goog-api-key` for Gemini); Azure's header follows the same wire that plan 4B's `AzureAdapter` takes (`StyleOptions.wire`, else `AzureRouting.styleFor`); Vertex's base is `VertexUrls.base(location, project)` and its wire the row's style, or null, so that the Vertex adapter decides by the model ID; Mantle follows `MantleRouting`; a local Ollama server goes to its origin. `ProfileClient` waits at most 5 s until the allowlist holds the profile, refuses a switched-off borrowed sign-in and a LAN server without Android's permission, wraps the adapter in `JsonActions` for a model with JSON actions, limits each attempt to 30 s (its own timeout is a `Timeout`, while the caller's stop stays a `CancellationException`), retries D4's failures twice after 1 s and 3 s (or a `Retry-After` of at most 10 s), never retries a `QuotaOrPolicy`, marks the ChatGPT plan's 429 in `planLimitReached` until a later call works, and prices each answer into the month's spending; plan sign-ins count tokens but no credits. Each call reads the profile from the applied setting by its ID and waits only until the allowlist holds at least the client's hosts, so that a client that sub-project 5 keeps follows a new Copilot base; resolving the target has the 30 s limit of an attempt. Following plan 4B, a Vertex key whose project ID is not in Google's form and a Mantle region that is not AWS's form fail with `NotAllowed`, Azure counts a deployment as Claude only when its name starts with `claude`, and Mantle's key header comes from its choice.

- [ ] **Step 1: Write the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/client/ProfileClientTest.kt`:

```kotlin
package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.SignInFixtures.catalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/** ProfileClient's limit per attempt, its retries, its checks before a request, and its cost (design D4, D11, D14, D17). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileClientTest {
    private val request = ChatRequest("You are a test.", listOf(ChatMessage.User(listOf(Part.Text("Hi")))), maxOutputTokens = 100)
    private val answer = ChatResponse(ChatMessage.Assistant(listOf(AssistantPart.Text("OK"))), StopReason.END, Usage(1_000, 500), "test-model")
    private val spent = mutableListOf<Triple<String, Double, Long>>()
    private val calls = mutableListOf<Long>()

    private fun profile(provider: String = "anthropic", choice: String = "apiKey", method: AuthMethod = AuthMethod.API_KEY, base: String? = null) =
        Profile(
            "test-profile", "Test", provider, choice, method, "test-credential", baseUrl = base,
            localServer = if (base != null) LocalServerKind.OLLAMA else null,
            lightweight = ProfileModel("test-model", price = Price(input = 1.0, output = 2.0)),
            versatile = ProfileModel("test-model"),
        )

    private fun target(base: String = "https://api.anthropic.com") =
        ModelTarget("anthropic", RequestStyle.ANTHROPIC, base.toHttpUrl(), ModelEntry("test-model", "Test model"), auth = NoAuth)

    /** An adapter that runs one step per call, in order; each step answers, throws or waits. */
    private fun TestScope.adapter(vararg steps: suspend () -> ChatResponse) = object : ChatAdapter {
        private var next = 0

        override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
            calls += currentTime
            return steps[next++]()
        }
    }

    private fun client(
        profile: Profile,
        adapter: ChatAdapter,
        applied: MutableStateFlow<ProvidersSetting?> = MutableStateFlow(ProvidersSetting(listOf(profile))),
        target: ModelTarget = target(),
        lan: Boolean = true,
        limits: PlanLimits = NoLimits,
    ) = ProfileClient(
        profile, catalog, applied, { _, _ -> target }, { adapter }, { lan },
        spend = { id, _, credits, tokens -> spent += Triple(id, credits, tokens) }, limits = limits, clock = { 1_800_000_000_000L },
    )

    private object NoLimits : PlanLimits {
        override fun reached(profileId: String) = Unit

        override fun cleared(profileId: String) = Unit
    }

    private suspend fun errorOf(block: suspend () -> Unit): ProviderError =
        try {
            block()
            fail("expected a ProviderException")
            throw AssertionError()
        } catch (e: ProviderException) {
            e.error
        }

    @Test
    fun anAnswerIsPricedAndItsCreditsAndTokensAreAdded() = runTest {
        val priced = client(profile(), adapter({ answer })).complete(ModelRole.LIGHTWEIGHT, request)

        // 1,000 input tokens at $1 and 500 output tokens at $2 per million: $0.002, which is 0.2 credits.
        assertEquals(0.002, priced.cost.usd, 1e-9)
        assertEquals(0.2, priced.cost.credits, 1e-9)
        assertEquals(RequestStyle.ANTHROPIC, priced.style)
        assertEquals("test-profile", spent.single().first)
        assertEquals(0.2, spent.single().second, 1e-9)
        assertEquals(1_500L, spent.single().third)
    }

    @Test
    fun anAttemptThatRunsOutOfTimeIsATimeoutAndIsTriedAgainAfterOneSecond() = runTest {
        val priced = client(profile(), adapter({ delay(31_000); answer }, { answer })).complete(ModelRole.LIGHTWEIGHT, request)

        assertEquals(answer, priced.response)
        assertEquals(listOf(0L, 31_000L), calls)
    }

    @Test
    fun aServerErrorIsTriedTwiceAfterOneAndThreeSecondsAndThenFails() = runTest {
        val failing: suspend () -> ChatResponse = { throw ProviderException(ProviderError.ServerError(503)) }

        val error = errorOf { client(profile(), adapter(failing, failing, failing)).complete(ModelRole.LIGHTWEIGHT, request) }

        assertEquals(ProviderError.ServerError(503), error)
        assertEquals(listOf(0L, 1_000L, 4_000L), calls)
    }

    @Test
    fun aRetryAfterOfAtMostTenSecondsIsWaitedForAndALongerOneFailsAtOnce() = runTest {
        val short: suspend () -> ChatResponse = { throw ProviderException(ProviderError.RateLimited(8_000)) }
        val long: suspend () -> ChatResponse = { throw ProviderException(ProviderError.RateLimited(11_000, "rate_limit")) }

        client(profile(), adapter(short, { answer })).complete(ModelRole.LIGHTWEIGHT, request)
        val error = errorOf { client(profile(), adapter(long)).complete(ModelRole.LIGHTWEIGHT, request) }

        assertEquals(ProviderError.RateLimited(11_000, "rate_limit"), error)
        assertEquals(listOf(0L, 8_000L, 8_000L), calls)
    }

    @Test
    fun theChatGptPlansUsageLimitIsNotRetriedAndHoldsUntilALaterCallWorks() = runTest {
        val plan = profile("openai", "chatgpt-plan", AuthMethod.BROWSER)
        val applied = MutableStateFlow<ProvidersSetting?>(ProvidersSetting(listOf(plan)))
        val limit = ProviderError.QuotaOrPolicy(429, "subscription_sharing_usage_limit_exceeded")
        val adapter = adapter({ throw ProviderException(limit) }, { answer })
        val repository = ProvidersRepository(MutableStateFlow(applied.value), applied) { profile, limits ->
            client(profile, adapter, applied, limits = limits)
        }

        assertEquals(limit, errorOf { repository.client("test-profile")!!.complete(ModelRole.LIGHTWEIGHT, request) })
        assertEquals(setOf("test-profile"), repository.planLimitReached.value)
        assertEquals(1, calls.size)
        val priced = repository.client("test-profile")!!.complete(ModelRole.LIGHTWEIGHT, request)
        assertEquals(emptySet<String>(), repository.planLimitReached.value)
        // The plan pays for its requests, so they count tokens but no credits (design D14).
        assertTrue(priced.cost.planUsage)
        assertEquals(0.0, spent.single().second, 0.0)
        assertEquals(1_500L, spent.single().third)
    }

    @Test
    fun aCancelledCallerGetsACancellationException() = runTest {
        val call = async { client(profile(), adapter({ awaitCancellation() })).complete(ModelRole.LIGHTWEIGHT, request) }
        runCurrent()

        call.cancel()

        try {
            call.await()
            fail("expected a CancellationException")
        } catch (e: CancellationException) {
            assertEquals(1, calls.size)
        }
    }

    @Test
    fun aSwitchedOffSignInFailsAtOnce() = runTest {
        val xai = profile("xai", "xai-oauth", AuthMethod.DEVICE_CODE)

        val error = errorOf { client(xai, adapter({ answer })).complete(ModelRole.LIGHTWEIGHT, request) }

        assertEquals(ProviderError.SignInSwitchedOff("xai"), error)
        assertEquals(0, calls.size)
    }

    @Test
    fun aServerOnTheOwnersNetworkNeedsThePermissionAndOneOnThePhoneDoesNot() = runTest {
        val lan = profile("ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, base = "http://192.168.1.20:11434")
        val phone = profile("ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, base = "http://127.0.0.1:11434")

        val error = errorOf { client(lan, adapter({ answer }), target = target("http://192.168.1.20:11434"), lan = false).complete(ModelRole.LIGHTWEIGHT, request) }
        client(phone, adapter({ answer }), target = target("http://127.0.0.1:11434"), lan = false).complete(ModelRole.LIGHTWEIGHT, request)

        assertEquals(ProviderError.LocalNetworkNotAllowed, error)
        assertEquals(1, calls.size)
    }

    @Test
    fun theClientWaitsUntilTheAllowlistHoldsTheProfileAndFailsAfterFiveSeconds() = runTest {
        val applied = MutableStateFlow<ProvidersSetting?>(null)
        val call = async { client(profile(), adapter({ answer }), applied).complete(ModelRole.LIGHTWEIGHT, request) }
        advanceTimeBy(2_000)
        applied.value = ProvidersSetting(listOf(profile()))
        assertEquals(answer, call.await().response)

        val error = errorOf { client(profile(), adapter({ answer }), MutableStateFlow(ProvidersSetting())).complete(ModelRole.LIGHTWEIGHT, request) }
        assertEquals(ProviderError.NotAllowed, error)
        assertEquals(listOf(2_000L), calls)

        // A stored profile that gained a host, as after a new Copilot base, still holds this client's hosts, and the call
        // resolves the profile as the allowlist holds it.
        val moved = profile().copy(discoveredHosts = listOf("api.example.com"))
        val resolved = mutableListOf<Profile>()
        val later = adapter({ answer })
        ProfileClient(
            profile(), catalog, MutableStateFlow(ProvidersSetting(listOf(moved))), { found, _ -> target().also { resolved += found } }, { later },
            { true }, spend = { _, _, _, _ -> }, limits = NoLimits,
        ).complete(ModelRole.LIGHTWEIGHT, request)
        assertEquals(listOf(moved), resolved)
    }

    @Test
    fun theDefaultClientIsNullOnlyWithoutADefaultProfile() {
        val with = MutableStateFlow<ProvidersSetting?>(ProvidersSetting(listOf(profile()), defaultProfileId = "test-profile"))
        val without = MutableStateFlow<ProvidersSetting?>(ProvidersSetting(listOf(profile())))

        assertEquals("test-profile", ProvidersRepository(with, with) { p, l -> client(p, NeverAdapter, limits = l) }.defaultClient()?.profile?.id)
        assertEquals(null, ProvidersRepository(without, without) { p, l -> client(p, NeverAdapter, limits = l) }.defaultClient())
    }

    private object NeverAdapter : ChatAdapter {
        override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse = error("not called")
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/client/TargetResolverTest.kt`:

```kotlin
package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.signin.SignInFixtures.catalog
import dev.kestrel.providers.styles.AzureRouting
import dev.kestrel.providers.styles.MantleRouting
import dev.kestrel.providers.styles.VertexUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** TargetResolver's base URLs, key headers, options and headers per choice (design D4, D9 and D13). */
class TargetResolverTest {
    private val vault = object : CredentialVault {
        val stored = mutableMapOf<String, Credential>("test-credential" to Credential.ApiKey("test-key"))

        override suspend fun read(id: String): Credential? = stored[id]

        override suspend fun write(id: String, credential: Credential) {
            stored[id] = credential
        }

        override suspend fun delete(id: String) {
            stored.remove(id)
        }
    }
    private val http = ProviderHttp(Call.Factory { error("no request in these tests") })
    private val credentials = CredentialManager(vault, http, catalog, CoroutineScope(Dispatchers.Default))
    private val request = ChatRequest("", listOf(ChatMessage.User(listOf(Part.Text("Hi")))), maxOutputTokens = 16)
    private val probe = HttpRequest("POST", "https://example.com/".toHttpUrl())

    private fun profile(
        provider: String,
        choice: String,
        method: AuthMethod = AuthMethod.API_KEY,
        model: ProfileModel = ProfileModel("test-model"),
        base: String? = null,
        region: String? = null,
        server: LocalServerKind? = null,
        credential: String = "test-credential",
    ) = Profile("test-profile", "Test", provider, choice, method, credential, base, region, server, model, model)

    private fun resolve(profile: Profile, discovered: List<DiscoveredModel> = emptyList(), special: Map<String, ProviderTargets> = emptyMap()) =
        runBlocking { TargetResolver(catalog, credentials, { discovered }, special).resolve(profile, ModelRole.LIGHTWEIGHT) }

    private fun headersOf(target: ModelTarget) = target.headers.headers(request)

    private fun authOf(target: ModelTarget) = runBlocking { target.auth.headers(probe) }

    @Test
    fun anXaiKeyRequestCarriesNoneOfTheGrokCliHeaders() {
        val target = resolve(profile("xai", "xai-api-key", model = ProfileModel("grok-4.7")))

        assertEquals("https://api.x.ai/v1", target.baseUrl.toString())
        assertEquals(RequestStyle.RESPONSES, target.style)
        assertTrue(headersOf(target).keys.none { it.lowercase() in setOf("x-xai-token-auth", "x-grok-client-version", "x-grok-model-override") })
        assertEquals(mapOf("Authorization" to "Bearer test-key"), authOf(target))
    }

    @Test
    fun anXaiSignInGoesToTheProxyWithItsHeadersAndTheModelOverride() {
        vault.stored["test-credential"] = Credential.OAuthToken("test-access", "test-refresh", null, tokenEndpoint = "https://auth.x.ai/oauth2/token")

        val target = resolve(profile("xai", "xai-oauth", AuthMethod.DEVICE_CODE, ProfileModel("grok-4.7")))

        assertEquals("https://cli-chat-proxy.grok.com/v1", target.baseUrl.toString())
        assertEquals(
            mapOf("X-XAI-Token-Auth" to "xai-grok-cli", "x-grok-client-version" to "1.0.13", "x-grok-model-override" to "grok-4.7"),
            headersOf(target),
        )
        assertEquals(mapOf("Authorization" to "Bearer test-access"), authOf(target))
    }

    @Test
    fun theChatGptPlanUsesResponsesAtTheApiWithItsPreviewOption() {
        val plain = resolve(profile("openai", "openai-api-key", model = ProfileModel("gpt-6-luna")))
        vault.stored["test-plan-credential"] = Credential.OAuthToken("test-access", "test-refresh", null, clientId = "oaiapp_test")
        val plan = resolve(profile("openai", "chatgpt-plan", AuthMethod.BROWSER, ProfileModel("gpt-6-luna"), credential = "test-plan-credential"))

        assertEquals("https://api.openai.com/v1", plan.baseUrl.toString())
        assertEquals(RequestStyle.RESPONSES, plan.style)
        assertTrue(plan.options.chatgptPlan)
        assertFalse(plain.options.chatgptPlan)
        assertEquals(mapOf("Authorization" to "Bearer test-access"), authOf(plan))
    }

    @Test
    fun eachStyleGetsItsDefaultKeyHeaderUnlessTheChoiceNamesOne() {
        val anthropic = resolve(profile("anthropic", "apiKey", model = ProfileModel("claude-test")))
        val miniMax = resolve(profile("minimax", "minimax-global-api", model = ProfileModel("MiniMax-M3")))
        val gemini = resolve(profile("google", "gemini-api-key", model = ProfileModel("gemini-test")))

        assertEquals(mapOf("x-api-key" to "test-key"), authOf(anthropic))
        assertEquals(RequestStyle.ANTHROPIC, miniMax.style)
        assertEquals("https://api.minimax.io/anthropic", miniMax.baseUrl.toString())
        assertEquals(mapOf("Authorization" to "Bearer test-key"), authOf(miniMax))
        assertEquals(mapOf("x-goog-api-key" to "test-key"), authOf(gemini))
    }

    @Test
    fun mantleAndAzureFollowTheirRouting() {
        val mantle = resolve(profile("amazon-bedrock-mantle", "mantle-api-key", model = ProfileModel("anthropic.claude-sonnet-5"), region = "us-east-1"))
        val azure = resolve(profile("microsoft-foundry", "microsoft-foundry-apikey", model = ProfileModel("gpt-6-luna"), base = "https://test.openai.azure.com/openai/v1"))
        val chosen = resolve(
            profile("microsoft-foundry", "microsoft-foundry-apikey", model = ProfileModel("my-model", style = RequestStyle.ANTHROPIC), base = "https://test.services.ai.azure.com"),
        )

        val route = MantleRouting.route("anthropic.claude-sonnet-5", "us-east-1")
        assertEquals(route.style, mantle.style)
        assertEquals(route.baseUrl, mantle.baseUrl)
        assertEquals(RequestStyle.AZURE, azure.style)
        assertEquals(AzureRouting.normalise("https://test.openai.azure.com/openai/v1"), azure.baseUrl)
        assertEquals(AzureRouting.styleFor("gpt-6-luna"), azure.options.wire)
        assertEquals(mapOf("api-key" to "test-key"), authOf(azure))
        assertEquals(RequestStyle.ANTHROPIC, chosen.options.wire)
        assertEquals(mapOf("x-api-key" to "test-key"), authOf(chosen))
        // Plan 4B counts a deployment as Claude only when its name starts with "claude".
        val named = resolve(profile("microsoft-foundry", "microsoft-foundry-apikey", model = ProfileModel("claude-team"), base = "https://test.services.ai.azure.com"))
        assertEquals(RequestStyle.ANTHROPIC, named.options.wire)
        assertEquals(mapOf("x-api-key" to "test-key"), authOf(named))
    }

    @Test
    fun vertexUsesTheLocationAndTheKeyFilesProject() {
        vault.stored["test-credential"] = Credential.ServiceAccountKey("test@example.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----", null, "test-project")
        val claude = catalog.provider("google-vertex")!!.models.first { it.style == RequestStyle.ANTHROPIC }

        val target = resolve(profile("google-vertex", "vertex-service-account", AuthMethod.IMPORTED, ProfileModel(claude.id), region = "europe-west4"))

        assertEquals(RequestStyle.VERTEX, target.style)
        assertEquals(VertexUrls.base("europe-west4", "test-project"), target.baseUrl)
        assertEquals(RequestStyle.ANTHROPIC, target.options.wire)
        // A project ID that is not in Google's form never becomes part of the path.
        vault.stored["test-credential-2"] = Credential.ServiceAccountKey("test@example.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----", null, "Bad/Project")
        try {
            resolve(profile("google-vertex", "vertex-service-account", AuthMethod.IMPORTED, ProfileModel(claude.id), region = "europe-west4", credential = "test-credential-2"))
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.NotAllowed, e.error)
        }
    }

    @Test
    fun aLocalOllamaServerUsesItsOriginAndTheOwnersSwitches() {
        val target = resolve(
            profile(
                "ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, ProfileModel("llama-test", images = true, tools = false),
                base = "http://192.168.1.20:11434/v1", server = LocalServerKind.OLLAMA,
            ),
        )

        assertEquals(RequestStyle.OLLAMA, target.style)
        assertEquals("http://192.168.1.20:11434/", target.baseUrl.toString())
        assertEquals(ToolMode.JSON, target.model.toolMode)
        assertTrue(InputKind.IMAGE in target.model.input)
    }

    @Test
    fun aModelThatOnlyTheListKnowsGetsWhatTheListReportsAndJsonActionsAreTheOwnersChoice() {
        val listed = DiscoveredModel("listed-model", "Listed", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, reasoningEfforts = listOf("low", "high"), tools = true)
        val target = resolve(profile("groq", "groq-api-key", model = ProfileModel("listed-model", jsonActions = true)), listOf(listed))

        assertEquals(setOf(InputKind.TEXT, InputKind.IMAGE), target.model.input)
        assertEquals(listOf("low", "high"), target.model.compat.supportedReasoningEfforts)
        assertEquals(ToolMode.JSON, target.model.toolMode)
        assertEquals(ModelEntry("x", "x"), ModelEntries.entryFor(null, "x", emptyList()))
    }

    @Test
    fun aProviderWithItsOwnTargetsResolvesThemItself() {
        val own = ModelTarget("github-copilot", RequestStyle.RESPONSES, "https://api.individual.githubcopilot.com".toHttpUrl(), ModelEntry("m", "m"), auth = NoAuth)

        val target = resolve(profile("github-copilot", "github-copilot", AuthMethod.DEVICE_CODE), special = mapOf("github-copilot" to ProviderTargets { _, _, _ -> own }))

        assertEquals(own, target)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/client/LocalNetworkTest.kt`:

```kotlin
package dev.kestrel.providers.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun onlyAHostOnTheOwnersNetworkNeedsThePermission() {
        assertTrue(LocalNetwork.needsPermission("192.168.1.20"))
        assertTrue(LocalNetwork.needsPermission("studio.local"))
        assertTrue(LocalNetwork.needsPermission("fd00::1"))
        assertFalse(LocalNetwork.needsPermission("127.0.0.1"))
        assertFalse(LocalNetwork.needsPermission("localhost"))
        assertFalse(LocalNetwork.needsPermission("::1"))
        assertFalse(LocalNetwork.needsPermission("api.openai.com"))
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'ProfileClient'", "Unresolved reference 'TargetResolver'" and "Unresolved reference 'LocalNetwork'".

- [ ] **Step 3: Write the local network rule and the target resolver**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/client/LocalNetworkAccess.kt`:

```kotlin
package dev.kestrel.providers.client

import dev.kestrel.providers.http.Hosts

/** Whether Kestrel may reach a host on the owner's network now; `:app` asks Android for ACCESS_LOCAL_NETWORK (design D11). */
fun interface LocalNetworkAccess {
    fun allowed(host: String): Boolean
}

/** Which hosts need Android 17's local-network permission (design D11; prototype P5). */
object LocalNetwork {
    /** Prototype P5 found that loopback needs no permission, granted, revoked or undeclared (q3 and q7). */
    const val LOOPBACK_NEEDS_PERMISSION: Boolean = false

    /** Whether a request to [host] needs the permission: a host on the owner's network, and loopback only if it needed it. */
    fun needsPermission(host: String): Boolean =
        Hosts.isLocal(host) && (LOOPBACK_NEEDS_PERMISSION || !Hosts.isLoopback(host))
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/client/TargetResolver.kt`:

```kotlin
package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestHeaders
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.styles.AzureRouting
import dev.kestrel.providers.styles.MantleRouting
import dev.kestrel.providers.styles.VertexUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The targets of a provider whose requests need more than the catalog, such as Copilot's (design D12). */
fun interface ProviderTargets {
    suspend fun target(profile: Profile, role: ModelRole, entry: ModelEntry): ModelTarget
}

/**
 * Resolves a profile's model for a role into a [ModelTarget] (design D4, D13 and "Shared names"): the style's base URL from
 * [ProfileHosts.baseUrl], the one rule that the allowlist uses too; the key header from the profile's choice, or the
 * style's default; the choice's ChatGPT plan switch; Azure's wire and key header; Mantle through MantleRouting; Vertex at
 * the location and the key file's project; the manifest's and the choice's headers, and xAI's model override on the Grok
 * CLI proxy. [models] gives a profile's last model list; [special] resolves the providers that need more, by provider ID.
 */
class TargetResolver(
    private val catalog: Catalog,
    private val credentials: CredentialManager,
    private val models: suspend (profileId: String) -> List<DiscoveredModel> = { emptyList() },
    private val special: Map<String, ProviderTargets> = emptyMap(),
) {
    suspend fun resolve(profile: Profile, role: ModelRole): ModelTarget {
        val chosen = profile.model(role)
        val provider = catalog.provider(profile.providerId)
        val entry = ModelEntries.withOwnerChoices(ModelEntries.entryFor(provider, chosen.id, models(profile.id)), chosen)
        special[profile.providerId]?.let { return it.target(profile, role, entry) }
        return when {
            profile.localServer != null -> localServer(profile, entry)
            profile.providerId == MANTLE -> mantle(profile, entry)
            provider?.style == RequestStyle.VERTEX -> vertex(profile, entry)
            provider?.style == RequestStyle.AZURE -> azure(profile, chosen, entry)
            else -> plain(profile, provider, chosen, entry)
        }
    }

    private suspend fun plain(profile: Profile, provider: ProviderEntry?, chosen: ProfileModel, entry: ModelEntry): ModelTarget {
        val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
        val style = chosen.style ?: entry.style ?: choice?.style ?: provider?.style ?: throw ProviderException(ProviderError.NotAllowed)
        val ownBase = profile.baseUrl == null && choice?.baseUrl == null
        val base = entry.baseUrl?.takeIf { ownBase }?.toHttpUrlOrNull() ?: ProfileHosts.baseUrl(profile, catalog)
            ?: throw ProviderException(ProviderError.NotAllowed)
        val fixed = provider?.headers.orEmpty() + choice?.identityHeaders.orEmpty()
        // The Grok CLI proxy serves the model that this header names (research r31); xAI's API keys never send it.
        val headers = if (base.host == GROK_PROXY) fixed + ("x-grok-model-override" to entry.id) else fixed
        return ModelTarget(
            providerId = profile.providerId,
            style = style,
            baseUrl = base,
            model = entry,
            auth = credentials.auth(profile, choice?.keyHeader ?: defaultKeyHeader(style)),
            headers = fixedHeaders(headers),
            options = StyleOptions(chatgptPlan = choice?.chatgptPlan == true),
        )
    }

    private suspend fun localServer(profile: Profile, entry: ModelEntry): ModelTarget {
        val style = catalog.localServers.firstOrNull { it.kind == profile.localServer }?.style ?: RequestStyle.CHAT_COMPLETIONS
        val address = profile.baseUrl?.toHttpUrlOrNull() ?: throw ProviderException(ProviderError.NotAllowed)
        // Ollama's native API lives at the server's origin, so a trailing /v1 of the address goes (design D11).
        val base = if (style == RequestStyle.OLLAMA) address.newBuilder().encodedPath("/").build() else address
        return ModelTarget(profile.providerId, style, base, entry, auth = credentials.auth(profile, KeyHeader.BEARER))
    }

    private suspend fun mantle(profile: Profile, entry: ModelEntry): ModelTarget {
        val region = profile.region ?: throw ProviderException(ProviderError.NotAllowed)
        val route = try {
            MantleRouting.route(entry.id, region)
        } catch (e: IllegalArgumentException) {
            // A region that is not one of AWS's names never becomes part of a host.
            throw ProviderException(ProviderError.NotAllowed)
        }
        // The bearer is written in overrides.json, not hidden in code (design D4).
        val header = catalog.choice(profile.providerId, profile.choiceId)?.settings?.keyHeader ?: KeyHeader.BEARER
        return ModelTarget(profile.providerId, route.style, route.baseUrl, entry, auth = credentials.auth(profile, header))
    }

    private suspend fun vertex(profile: Profile, entry: ModelEntry): ModelTarget {
        val key = profile.credentialId?.let { credentials.read(it) } as? Credential.ServiceAccountKey
            ?: throw ProviderException(ProviderError.SignInExpired(profile.providerId))
        val location = profile.region?.takeIf(VertexUrls::validLocation) ?: throw ProviderException(ProviderError.NotAllowed)
        // VertexUrls.base refuses a project ID that is not in Google's form (plan 4B), because it becomes part of the path.
        val project = key.projectId.takeIf(VertexUrls::validProject) ?: throw ProviderException(ProviderError.NotAllowed)
        return ModelTarget(
            providerId = profile.providerId,
            style = RequestStyle.VERTEX,
            baseUrl = VertexUrls.base(location, project),
            model = entry,
            auth = credentials.auth(profile, KeyHeader.BEARER),
            // The Vertex adapter takes the row's wire, and else decides by the model ID (plan 4B).
            options = StyleOptions(wire = entry.style),
        )
    }

    private suspend fun azure(profile: Profile, chosen: ProfileModel, entry: ModelEntry): ModelTarget {
        val endpoint = profile.baseUrl?.let(AzureRouting::normalise) ?: throw ProviderException(ProviderError.NotAllowed)
        // The same wire as AzureAdapter's: the owner's choice, else a deployment whose name starts with "claude" is Claude.
        val wire = chosen.style ?: AzureRouting.styleFor(entry.id)
        val header = if (wire == RequestStyle.ANTHROPIC) KeyHeader.X_API_KEY else KeyHeader.API_KEY
        return ModelTarget(
            providerId = profile.providerId,
            style = RequestStyle.AZURE,
            baseUrl = endpoint,
            model = entry,
            auth = credentials.auth(profile, header),
            options = StyleOptions(wire = wire),
        )
    }

    private fun fixedHeaders(headers: Map<String, String>): RequestHeaders =
        if (headers.isEmpty()) RequestHeaders.NONE else RequestHeaders { headers }

    companion object {
        const val MANTLE = "amazon-bedrock-mantle"
        const val GROK_PROXY = "cli-chat-proxy.grok.com"

        /** The key header of each style when the choice names none (design D4). */
        fun defaultKeyHeader(style: RequestStyle): KeyHeader = when (style) {
            RequestStyle.ANTHROPIC -> KeyHeader.X_API_KEY
            RequestStyle.GEMINI -> KeyHeader.X_GOOG_API_KEY
            RequestStyle.AZURE -> KeyHeader.API_KEY
            RequestStyle.CHAT_COMPLETIONS, RequestStyle.RESPONSES, RequestStyle.OLLAMA, RequestStyle.BEDROCK, RequestStyle.VERTEX -> KeyHeader.BEARER
        }
    }
}

/** A profile's model as the catalog row that the adapters read (design D7 and D13). */
object ModelEntries {
    /**
     * The catalog's row of [modelId], with its price, capabilities and compat, and the style and the lack of tool support
     * that the provider's list reports (Copilot's per-model style); else the row that the list gives, with the input kinds,
     * reasoning, efforts, limits and tool support that it reports; else a row with only the ID, text input and the style's
     * defaults.
     */
    fun entryFor(provider: ProviderEntry?, modelId: String, discovered: List<DiscoveredModel>): ModelEntry {
        val row = provider?.models?.firstOrNull { it.id == modelId }
        val found = discovered.firstOrNull { it.id == modelId }
        return when {
            row != null -> row.copy(style = found?.style ?: row.style, toolMode = if (found?.tools == false) ToolMode.JSON else row.toolMode)
            found != null -> fromDiscovered(found)
            else -> ModelEntry(modelId, modelId)
        }
    }

    /** A row from a model that only the provider's list knows; a model that reports no tool support gets JSON actions. */
    fun fromDiscovered(found: DiscoveredModel): ModelEntry =
        ModelEntry(
            id = found.id,
            name = found.name,
            style = found.style,
            input = found.input,
            reasoning = found.reasoning,
            contextWindow = found.contextWindow,
            maxTokens = found.maxTokens,
            compat = CompatFlags(supportsTools = found.tools, supportedReasoningEfforts = found.reasoningEfforts),
            toolMode = if (found.tools == false) ToolMode.JSON else ToolMode.NATIVE,
        )

    /** The owner's switches on the profile page: "Use JSON actions", and a local server's "Accepts images" and "Handles tool calls". */
    fun withOwnerChoices(entry: ModelEntry, chosen: ProfileModel): ModelEntry {
        val input = when (chosen.images) {
            true -> entry.input + InputKind.IMAGE
            false -> entry.input - InputKind.IMAGE
            null -> entry.input
        }
        val json = chosen.jsonActions || chosen.tools == false || (chosen.tools == null && entry.toolMode == ToolMode.JSON)
        return entry.copy(input = input, toolMode = if (json) ToolMode.JSON else ToolMode.NATIVE)
    }
}
```

- [ ] **Step 4: Write the client and the repository**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/client/ProfileClient.kt`:

```kotlin
package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Cost
import dev.kestrel.providers.cost.CostMeter
import dev.kestrel.providers.cost.PriceTable
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.styles.JsonActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneOffset

/** A provider's answer with its cost and the style that carried it (design D14). toString prints no reply. */
data class PricedResponse(val response: ChatResponse, val cost: Cost, val style: RequestStyle) {
    override fun toString(): String = "PricedResponse(response=$response, cost=$cost, style=$style)"
}

/** Where ProfileClient reports that a plan's usage limit was reached, and that a later call worked (design D17). */
interface PlanLimits {
    fun reached(profileId: String)

    fun cleared(profileId: String)
}

/**
 * What sub-project 5 calls for one profile (design D4, D11, D13 and D14). [complete] waits at most 5 s until the network
 * allowlist holds the profile's hosts ([applied], which `:app`'s allowlist job publishes), refuses a profile whose borrowed
 * sign-in is switched off and a server on the owner's network without Android's permission, resolves the role's model
 * with [targets], runs the adapter (inside JsonActions for a model with JSON actions) with a limit of 30 s per attempt,
 * and retries a timeout, a rate limit, a server error or an unreachable provider twice, after 1 s and 3 s or after a
 * `Retry-After` of at most 10 s. A longer `Retry-After` fails at once, so that sub-project 5 can switch models; a
 * QuotaOrPolicy is never retried, and on a ChatGPT plan profile its 429 marks the plan's usage limit until a later call
 * works. Each answer is priced, and its credits and tokens are added to the month's spending. A stop reaches the caller as
 * a CancellationException, unchanged. Each call uses the profile as the applied setting holds it, so that a client that
 * sub-project 5 keeps sees a new Copilot base or a new sign-in; resolving the target has the limit of one attempt.
 */
class ProfileClient(
    val profile: Profile,
    private val catalog: Catalog,
    private val applied: StateFlow<ProvidersSetting?>,
    private val targets: suspend (Profile, ModelRole) -> ModelTarget,
    private val adapters: (RequestStyle) -> ChatAdapter,
    private val localNetwork: LocalNetworkAccess,
    private val spend: suspend (profileId: String, month: String, credits: Double, tokens: Long) -> Unit,
    private val limits: PlanLimits,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val prices = PriceTable(catalog)

    suspend fun complete(role: ModelRole, request: ChatRequest): PricedResponse {
        val hosts = ProfileHosts.hosts(profile, catalog)
        // A stored profile may gain hosts while this client exists, as after a new Copilot base, so the allowlist must hold
        // at least this profile's hosts (design D13).
        val setting = withTimeoutOrNull(ALLOWLIST_WAIT_MILLIS) {
            applied.first { applied -> applied?.profiles?.any { it.id == profile.id && ProfileHosts.hosts(it, catalog).containsAll(hosts) } == true }
        } ?: throw ProviderException(ProviderError.NotAllowed)
        val current = setting.profiles.first { it.id == profile.id }
        if (!setting.usable(current, catalog)) throw ProviderException(ProviderError.SignInSwitchedOff(profile.providerId))
        // Copilot's discovery is a network call, so the target has the limit of one attempt too (design D4).
        val target = withTimeoutOrNull(ATTEMPT_MILLIS) { targets(current, role) } ?: throw ProviderException(ProviderError.Timeout)
        val host = target.baseUrl.host
        if (LocalNetwork.needsPermission(host) && !localNetwork.allowed(host)) throw ProviderException(ProviderError.LocalNetworkNotAllowed)
        val adapter = adapters(target.style).let { if (target.model.toolMode == ToolMode.JSON) JsonActions(it) else it }
        val plan = catalog.choice(profile.providerId, profile.choiceId)?.settings?.chatgptPlan == true
        var attempt = 0
        while (true) {
            val failure = try {
                val response = withTimeoutOrNull(ATTEMPT_MILLIS) { adapter.complete(request, target) }
                if (response != null) {
                    limits.cleared(profile.id)
                    return priced(response, target)
                }
                ProviderError.Timeout
            } catch (e: ProviderException) {
                val error = e.error
                if (plan && error is ProviderError.QuotaOrPolicy && error.status == 429) limits.reached(profile.id)
                error
            }
            val wait = retryWait(failure, attempt) ?: throw ProviderException(failure)
            attempt++
            pause(wait)
        }
    }

    private suspend fun priced(response: ChatResponse, target: ModelTarget): PricedResponse {
        val now = clock()
        val day = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val choice = prices.priceFor(profile, target.model.id, target.model.name, day)
        val cost = CostMeter.cost(response.usage, choice)
        spend(profile.id, Spending.monthOf(now), cost.credits, response.usage.totalInput + response.usage.output)
        return PricedResponse(response, cost, target.style)
    }

    /** The pause before the next attempt, or null when [error] ends the call (design D4). */
    private fun retryWait(error: ProviderError, attempt: Int): Long? {
        if (attempt >= RETRY_PAUSES.size) return null
        return when (error) {
            ProviderError.Timeout, ProviderError.Unreachable, is ProviderError.ServerError -> RETRY_PAUSES[attempt]
            is ProviderError.RateLimited -> error.retryAfterMillis?.let { after -> after.takeIf { it <= MAX_RETRY_AFTER_MILLIS } ?: return null }
                ?: RETRY_PAUSES[attempt]
            else -> null
        }
    }

    override fun toString(): String = "ProfileClient(profile=$profile)"

    private companion object {
        const val ALLOWLIST_WAIT_MILLIS = 5_000L
        const val ATTEMPT_MILLIS = 30_000L
        const val MAX_RETRY_AFTER_MILLIS = 10_000L
        val RETRY_PAUSES = listOf(1_000L, 3_000L)
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/client/ProvidersRepository.kt`:

```kotlin
package dev.kestrel.providers.client

import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProvidersSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Gives sub-project 5 the client of a profile (design D13 and D17). [stored] is the stored provider setting, [applied]
 * the one whose hosts the allowlist holds; [newClient] builds a profile's client. [defaultClient] is null only when there
 * is no default profile (spec 11.1); the client of a profile whose borrowed sign-in is switched off fails each call with
 * SignInSwitchedOff, so that sub-project 5 can say why. [planLimitReached] holds the IDs of the ChatGPT plan profiles
 * whose plan's usage limit a call reached, until a later call on them works; Kestrel never moves such a request to
 * another profile by itself. For a moment after the process starts, until the settings store has answered, both
 * functions return null although a profile exists; sub-project 5 waits for [stored] to hold a setting before it says
 * that no profile is set up.
 */
class ProvidersRepository(
    private val stored: StateFlow<ProvidersSetting?>,
    private val applied: StateFlow<ProvidersSetting?>,
    private val newClient: (Profile, PlanLimits) -> ProfileClient,
) {
    private val limited = MutableStateFlow<Set<String>>(emptySet())

    val planLimitReached: StateFlow<Set<String>> = limited.asStateFlow()

    private val limits = object : PlanLimits {
        override fun reached(profileId: String) = limited.update { it + profileId }

        override fun cleared(profileId: String) = limited.update { it - profileId }
    }

    private fun setting(): ProvidersSetting? = stored.value ?: applied.value

    fun defaultClient(): ProfileClient? = setting()?.defaultProfile?.let { newClient(it, limits) }

    fun client(profileId: String): ProfileClient? =
        setting()?.profiles?.firstOrNull { it.id == profileId }?.let { newClient(it, limits) }
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 51 tests (R₀ + 31 + 20): `ProfileClientTest` 10, `TargetResolverTest` 9 and `LocalNetworkTest` 1. `anAttemptThatRunsOutOfTimeIsATimeoutAndIsTriedAgainAfterOneSecond` runs in virtual time.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`, with `:core:providers` R₀ + 51 and the other modules as before, lint without issues, and no audit finding.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
$main = 'core/providers/src/main/kotlin/dev/kestrel/providers/client'
$test = 'core/providers/src/test/kotlin/dev/kestrel/providers/client'
git add "$main/LocalNetworkAccess.kt" "$main/TargetResolver.kt" "$main/ProfileClient.kt" "$main/ProvidersRepository.kt" "$test/ProfileClientTest.kt" "$test/TargetResolverTest.kt" "$test/LocalNetworkTest.kt"
git commit -m "feat(providers): add the target resolver, the profile client with its time limit and retries, and the repository" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Device codes

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/signin/DeviceCode.kt`, `Pkce.kt`, `GitHubDeviceFlow.kt`, `XaiDeviceFlow.kt`, `MiniMaxDeviceFlow.kt`
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/signin/GitHubDeviceFlowTest.kt`, `XaiDeviceFlowTest.kt`, `MiniMaxDeviceFlowTest.kt`, `PkceTest.kt`; fixtures `core/providers/src/test/resources/fixtures/github/device-code.json`, `github/token.json`, `xai/openid-configuration.json`, `xai/device-authorization.json`, `xai/token.json`, `minimax/device-code.json`, `minimax/token.json`

**Interfaces:**
- Consumes: Task 1's `SignInResult`, `SignInFailed`, `SignInFailure`, `SignInHttp`, `XaiAuth`, `MiniMaxAuth`, `MiniMaxRegion`, `TokenTimes`, `SignInFixtures`.
- Produces (package `dev.kestrel.providers.signin`): `data class DeviceCodePrompt(val userCode: String, val verificationUrl: String, val expiresAtMillis: Long, val intervalMillis: Long)`; `interface DeviceCodeFlow { val signInHosts: Set<String>; suspend fun start(): DeviceCodePrompt; suspend fun await(prompt: DeviceCodePrompt): SignInResult }`; `class GitHubDeviceFlow(domain: String, http: ProviderHttp, clock: () -> Long = …, sleep: suspend (Long) -> Unit = …)` with `companion object { const val CLIENT_ID; const val SCOPE; const val PUBLIC = "github.com"; const val DEVICE_GRANT; fun validDomain(domain: String): Boolean; fun tenant(input: String): String? }`; `class XaiDeviceFlow(http: ProviderHttp, openHosts: (Set<String>) -> AutoCloseable, clock, sleep)`; `class MiniMaxDeviceFlow(region: MiniMaxRegion, http: ProviderHttp, clock, sleep)`; `object Pkce { fun verifier(): String; fun challenge(verifier: String): String; fun state(): String; fun nonce(): String }`.

Design D9 and D22; research r27 and r31. Each flow is used once: `start` asks for a code, and `await` polls until the owner approves or refuses, or the code expires. Copilot's flow uses GitHub's own client on github.com or a GHE.com tenant, shows the fixed address `https://<domain>/login/device` (any other verification address ends the flow), sleeps before each poll, at least 1 s apart, and slows down by 5 s, or to a larger returned interval, at each `slow_down`; its token is the bearer of every Copilot call and puts `api.<domain>` into the profile's hosts. xAI's flow trusts only HTTPS addresses on `x.ai` from its OIDC discovery and leases its discovered hosts until it ends, and requires a refresh token and an expiry. MiniMax's flow sends PKCE and a `state` that must come back, decides by the body's `status`, reads `interval` below 100 as seconds and from 100 on as milliseconds with a floor of 2 s, and takes `resource_url` only on `minimax.io` or `minimaxi.com`. MiniMax's poll is decided by the body's `status` alone: any status other than 2xx (a 429 or a 5xx excepted) ends it, and `base_resp` is not read during the sign-in, as OpenClaw's poll parser does. The tests inject the sleep and the clock, so they wait for nothing.

- [ ] **Step 1: Write the fixtures and the failing tests**

Create `core/providers/src/test/resources/fixtures/github/device-code.json`:

```json
{
  "device_code": "test-device-code",
  "user_code": "WDJB-MJHT",
  "verification_uri": "https://github.com/login/device?test=1",
  "expires_in": 900,
  "interval": 5
}
```

Create `core/providers/src/test/resources/fixtures/github/token.json`:

```json
{
  "access_token": "test-github-token",
  "token_type": "bearer",
  "scope": "read:user"
}
```

Create `core/providers/src/test/resources/fixtures/xai/openid-configuration.json`:

```json
{
  "issuer": "https://auth.x.ai",
  "device_authorization_endpoint": "https://auth.x.ai/oauth2/device/code",
  "token_endpoint": "https://auth.x.ai/oauth2/token",
  "jwks_uri": "https://auth.x.ai/.well-known/jwks.json"
}
```

Create `core/providers/src/test/resources/fixtures/xai/device-authorization.json`:

```json
{
  "device_code": "test-device-code",
  "user_code": "ABCD-EFGH",
  "verification_uri": "https://accounts.x.ai/oauth2/device",
  "verification_uri_complete": "https://accounts.x.ai/oauth2/device?user_code=ABCD-EFGH",
  "expires_in": 600,
  "interval": 5
}
```

Create `core/providers/src/test/resources/fixtures/xai/token.json`:

```json
{
  "access_token": "test-xai-access-1",
  "refresh_token": "test-xai-refresh-1",
  "id_token": "test-id-token",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Create `core/providers/src/test/resources/fixtures/minimax/device-code.json`:

```json
{
  "user_code": "MM-1234",
  "verification_uri": "https://www.minimax.io/oauth/device?user_code=MM-1234",
  "expired_in": 600,
  "interval": 3000,
  "state": "STATE"
}
```

Create `core/providers/src/test/resources/fixtures/minimax/token.json`:

```json
{
  "status": "success",
  "access_token": "test-minimax-access-1",
  "refresh_token": "test-minimax-refresh-1",
  "expired_in": 7200,
  "resource_url": "https://api.minimax.io/anthropic",
  "base_resp": { "status_code": 0, "status_msg": "success" }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/GitHubDeviceFlowTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Copilot's device flow on github.com and a GHE.com tenant (design D9 and D12; research r27). */
class GitHubDeviceFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private var now = 1_000_000L
    private val pauses = mutableListOf<Long>()

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun flow(domain: String = "github.com") = GitHubDeviceFlow(domain, http, { now }) { pauses += it; now += it }

    private fun failureOf(block: suspend () -> Unit): SignInFailure =
        try {
            runBlocking { block() }
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e.kind
        }

    @Test
    fun aSignInPollsAfterEachIntervalAndSlowsDownWhenAsked() = runBlocking {
        server.enqueue(fixture(200, "github/device-code.json"))
        server.enqueue(json(200, """{"error":"authorization_pending"}"""))
        server.enqueue(json(200, """{"error":"slow_down","interval":12}"""))
        server.enqueue(json(200, """{"error":"authorization_pending"}"""))
        server.enqueue(fixture(200, "github/token.json"))
        val flow = flow()

        val prompt = flow.start()
        val result = flow.await(prompt)

        assertEquals(DeviceCodePrompt("WDJB-MJHT", "https://github.com/login/device", 1_000_000L + 900_000, 5_000), prompt)
        assertEquals(Credential.GitHubToken("test-github-token", "github.com"), result.credential)
        assertEquals(setOf("api.github.com"), result.hosts)
        assertEquals(listOf(5_000L, 5_000L, 12_000L, 12_000L), pauses)
        assertEquals("https://github.com/login/device/code", calls.requests.first().toString())
        assertEquals(4, calls.requests.count { it.toString() == "https://github.com/login/oauth/access_token" })
        assertEquals("client_id=Iv1.b507a08c87ecfe98&scope=read%3Auser", server.takeRequest().body!!.utf8())
        assertEquals(
            "client_id=Iv1.b507a08c87ecfe98&device_code=test-device-code&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun aTenantSignsInOnItsOwnDomain() = runBlocking {
        server.enqueue(json(200, read("github/device-code.json").replace("https://github.com/", "https://acme.ghe.com/")))
        server.enqueue(fixture(200, "github/token.json"))
        val flow = flow("acme.ghe.com")

        val result = flow.await(flow.start())

        assertEquals(Credential.GitHubToken("test-github-token", "acme.ghe.com"), result.credential)
        assertEquals(setOf("api.acme.ghe.com"), result.hosts)
        assertEquals(setOf("acme.ghe.com"), flow.signInHosts)
        assertEquals("https://acme.ghe.com/login/device/code", calls.requests.first().toString())
        assertEquals("acme.ghe.com", GitHubDeviceFlow.tenant("acme"))
        assertEquals("acme.ghe.com", GitHubDeviceFlow.tenant(" https://Acme.ghe.com/ "))
        assertNull(GitHubDeviceFlow.tenant("acme.example.com"))
        try {
            flow("api.example.com")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Not github.com or a GHE.com tenant", e.message)
        }
    }

    @Test
    fun aVerificationAddressOnAnotherHostIsRefusedAndTheIntervalHasAFloor() {
        server.enqueue(json(200, read("github/device-code.json").replace("https://github.com/login/device", "https://github.example.com/login/device")))
        server.enqueue(json(200, read("github/device-code.json").replace("\"interval\": 5", "\"interval\": 0")))

        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf { flow().start() })
        // The polls are at least 1 s apart.
        assertEquals(1_000L, runBlocking { flow().start() }.intervalMillis)
    }

    @Test
    fun refusalExpiryAndTheDeadlineEndTheSignIn() {
        server.enqueue(fixture(200, "github/device-code.json"))
        server.enqueue(json(200, """{"error":"access_denied"}"""))
        server.enqueue(fixture(200, "github/device-code.json"))
        server.enqueue(json(200, """{"error":"expired_token"}"""))
        server.enqueue(json(200, read("github/device-code.json").replace("\"expires_in\": 900", "\"expires_in\": 4")))

        assertEquals(SignInFailure.REFUSED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.EXPIRED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.EXPIRED, failureOf { flow().let { it.await(it.start()) } })
        // The last code expired before its first poll, so no poll was sent for it.
        assertEquals(5, calls.requests.size)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/XaiDeviceFlowTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** xAI's device flow through OIDC discovery (design D9; research r31). */
class XaiDeviceFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private var now = 1_000_000L
    private val pauses = mutableListOf<Long>()
    private val leases = mutableListOf<Set<String>>()
    private var open = 0

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun flow() = XaiDeviceFlow(http, { hosts -> leases += hosts; open++; AutoCloseable { open-- } }, { now }) { pauses += it; now += it }

    private fun failureOf(block: suspend () -> Unit): SignInFailure =
        try {
            runBlocking { block() }
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e.kind
        }

    @Test
    fun theSignInLeasesTheDiscoveredHostsPrefersTheCompleteAddressAndKeepsTheTokenEndpoint() = runBlocking {
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(400, """{"error":"slow_down"}"""))
        server.enqueue(fixture(200, "xai/token.json"))
        val flow = flow()

        val prompt = flow.start()
        val result = flow.await(prompt)

        assertEquals("https://accounts.x.ai/oauth2/device?user_code=ABCD-EFGH", prompt.verificationUrl)
        assertEquals(
            Credential.OAuthToken("test-xai-access-1", "test-xai-refresh-1", now + 3_600_000, tokenEndpoint = "https://auth.x.ai/oauth2/token"),
            result.credential,
        )
        assertEquals(setOf("auth.x.ai"), result.hosts)
        assertEquals(listOf(setOf("auth.x.ai")), leases)
        assertEquals(0, open)
        assertEquals(listOf(5_000L, 5_000L, 10_000L), pauses)
        assertEquals("https://auth.x.ai/.well-known/openid-configuration", calls.requests.first().toString())
    }

    @Test
    fun aDiscoveredEndpointOffXaiIsRefusedWithoutALease() {
        server.enqueue(json(200, read("xai/openid-configuration.json").replace("https://auth.x.ai/oauth2/token", "https://auth.example.com/oauth2/token")))

        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf { flow().start() })
        assertEquals(emptyList<Set<String>>(), leases)
    }

    @Test
    fun aVerificationAddressOffXaiIsRefusedAndTheLeaseClosed() {
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(json(200, read("xai/device-authorization.json").replace("https://accounts.x.ai/", "https://accounts.example.com/")))

        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf { flow().start() })
        assertEquals(0, open)
    }

    @Test
    fun aTokenWithoutARefreshTokenOrAnExpiryFailsAndARefusalEndsTheSignIn() {
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(200, """{"access_token":"test-xai-access-1","expires_in":3600}"""))
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(200, """{"access_token":"test-xai-access-1","refresh_token":"test-xai-refresh-1"}"""))
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(400, """{"error":"access_denied"}"""))
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(400, """{"error":"expired_token"}"""))

        assertEquals(SignInFailure.EXCHANGE_FAILED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.EXCHANGE_FAILED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.REFUSED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.EXPIRED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(0, open)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/MiniMaxDeviceFlowTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/** MiniMax's user-code grant with PKCE and its echoed state (design D9; research r31). */
class MiniMaxDeviceFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private var now = 1_000_000L
    private val pauses = mutableListOf<Long>()

    /** The answers to the polls, in order; the code request is answered with its own state unless [stateBack] is set. */
    private val polls = ArrayDeque<MockResponse>()
    private var codeAnswer = read("minimax/device-code.json")
    private var stateBack: String? = null
    private val forms = mutableListOf<Map<String, String>>()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body!!.utf8().split('&').associate { field ->
                    val (name, value) = field.split('=', limit = 2)
                    name to URLDecoder.decode(value, Charsets.UTF_8)
                }
                forms += form
                return if (request.url.encodedPath == "/oauth2/device/code") {
                    json(200, codeAnswer.replace("STATE", stateBack ?: form.getValue("state")))
                } else {
                    polls.removeFirst()
                }
            }
        }
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun flow(region: MiniMaxRegion = MiniMaxRegion.GLOBAL) = MiniMaxDeviceFlow(region, http, { now }) { pauses += it; now += it }

    private fun failureOf(block: suspend () -> Unit): SignInFailure =
        try {
            runBlocking { block() }
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e.kind
        }

    @Test
    fun aSignInSendsPkceAndItsStateReadsTheIntervalAsMillisecondsAndKeepsTheResourceUrl() = runBlocking {
        // A pending answer may carry a base_resp of its own; during the sign-in only the status decides.
        polls += json(200, """{"status":"pending","base_resp":{"status_code":1001,"status_msg":"pending"}}""")
        polls += json(200, read("minimax/token.json"))
        val flow = flow()

        val prompt = flow.start()
        val result = flow.await(prompt)

        assertEquals(DeviceCodePrompt("MM-1234", "https://www.minimax.io/oauth/device?user_code=MM-1234", 1_000_000L + 600_000, 3_000), prompt)
        assertEquals(
            Credential.OAuthToken(
                "test-minimax-access-1", "test-minimax-refresh-1", now + 7_200_000,
                tokenEndpoint = "https://account.minimax.io/oauth2/token", region = "global",
            ),
            result.credential,
        )
        assertEquals("https://api.minimax.io/anthropic", result.baseUrl)
        assertEquals(setOf("account.minimax.io", "api.minimax.io"), result.hosts)
        assertEquals(listOf(3_000L, 3_000L), pauses)
        val code = forms.first()
        assertEquals("78257093-7e40-4613-99e0-527b14b39113", code["client_id"])
        assertEquals("S256", code["code_challenge_method"])
        assertEquals("group_id profile model.completion", code["scope"])
        val poll = forms.last()
        assertEquals("urn:ietf:params:oauth:grant-type:user_code", poll["grant_type"])
        assertEquals("MM-1234", poll["user_code"])
        assertEquals(code["code_challenge"], Pkce.challenge(poll.getValue("code_verifier")))
        assertTrue(server.takeRequest().headers["x-request-id"]!!.isNotEmpty())
    }

    @Test
    fun aChangedStateIsRefusedAndAForeignResourceUrlFallsBackToTheRegionsRoute() = runBlocking {
        stateBack = "another-state"
        assertEquals(SignInFailure.EXCHANGE_FAILED, failureOf { flow().start() })

        stateBack = null
        codeAnswer = read("minimax/device-code.json").replace("3000", "3")
        polls += json(200, read("minimax/token.json").replace("https://api.minimax.io/anthropic", "https://api.example.com/anthropic"))
        val flow = flow(MiniMaxRegion.CHINA)
        val prompt = flow.start()
        val result = flow.await(prompt)

        assertEquals(3_000L, prompt.intervalMillis)
        assertEquals("https://api.minimaxi.com/anthropic", result.baseUrl)
        assertEquals("https://account.minimaxi.com/oauth2/token", calls.requests.last().toString())
        // An interval of 1 s is raised to the floor of 2 s, because MiniMax has no slow_down.
        codeAnswer = read("minimax/device-code.json").replace("3000", "1")
        assertEquals(2_000L, flow().start().intervalMillis)
    }

    @Test
    fun anErrorStatusIsARefusalAndTheDeadlineEndsTheSignIn() {
        polls += json(200, """{"status":"error","base_resp":{"status_code":1004,"status_msg":"denied"}}""")
        assertEquals(SignInFailure.REFUSED, failureOf { flow().let { it.await(it.start()) } })
        // A refused HTTP status ends the sign-in whatever its body says.
        polls += json(400, """{"status":"pending"}""")
        assertEquals(SignInFailure.REFUSED, failureOf { flow().let { it.await(it.start()) } })

        codeAnswer = read("minimax/device-code.json").replace("https://www.minimax.io/", "https://www.example.com/")
        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf { flow().start() })

        codeAnswer = read("minimax/device-code.json").replace("\"expired_in\": 600", "\"expired_in\": 2")
        assertEquals(SignInFailure.EXPIRED, failureOf { flow().let { it.await(it.start()) } })
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/PkceTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PkceTest {
    @Test
    fun theChallengeIsRfc7636sS256AndTheRandomValuesHaveTheirLengths() {
        // RFC 7636, Appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals(43, Pkce.verifier().length)
        assertEquals(22, Pkce.state().length)
        assertEquals(22, Pkce.nonce().length)
        assertNotEquals(Pkce.state(), Pkce.state())
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'GitHubDeviceFlow'", "Unresolved reference 'XaiDeviceFlow'", "Unresolved reference 'MiniMaxDeviceFlow'", "Unresolved reference 'DeviceCodePrompt'" and "Unresolved reference 'Pkce'".

- [ ] **Step 3: Write PKCE and the device-code types**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/Pkce.kt`:

```kotlin
package dev.kestrel.providers.signin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) and the random values of OAuth sign-ins (design D9). */
object Pkce {
    private val random = SecureRandom()

    /** 32 random bytes, base64url without padding. */
    fun verifier(): String = randomText(32)

    /** The S256 challenge of [verifier]: its SHA-256, base64url without padding. */
    fun challenge(verifier: String): String =
        encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** 16 random bytes, base64url without padding. */
    fun state(): String = randomText(16)

    /** 16 random bytes, base64url without padding. */
    fun nonce(): String = randomText(16)

    private fun randomText(bytes: Int): String = encode(ByteArray(bytes).also(random::nextBytes))

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/DeviceCode.kt`:

```kotlin
package dev.kestrel.providers.signin

/**
 * What the sign-in page shows while a device code waits (design D9): the code in large type, the address that the owner
 * opens, the time when the code expires and the poll interval. The code lets anyone approve the sign-in, so toString
 * prints only its length.
 */
data class DeviceCodePrompt(val userCode: String, val verificationUrl: String, val expiresAtMillis: Long, val intervalMillis: Long) {
    override fun toString(): String =
        "DeviceCodePrompt(userCodeLength=${userCode.length}, expiresAtMillis=$expiresAtMillis, intervalMillis=$intervalMillis)"
}

/**
 * A sign-in with a device code (design D9, RFC 8628): [start] asks for a code, and [await] polls until the owner approves
 * or refuses, or the code expires. A flow is used once. [signInHosts] are the hosts that the sign-in needs while it runs;
 * the caller leases them on the allowlist and closes the lease when the flow ends.
 */
interface DeviceCodeFlow {
    val signInHosts: Set<String>

    suspend fun start(): DeviceCodePrompt

    suspend fun await(prompt: DeviceCodePrompt): SignInResult
}
```

- [ ] **Step 4: Write the three flows**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/GitHubDeviceFlow.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Copilot's sign-in (design D9 and D12, research r27): GitHub's device flow with GitHub's own OAuth client, on github.com
 * or a GHE.com tenant. The verification address must be `https://<domain>/login/device`, and the page shows that fixed
 * address. Polls sleep first, at least 1 s apart and by default every 5 s; each `slow_down` adds 5 s, or raises the
 * interval to a returned `interval` when that is larger. The token never expires on its own and is the bearer of every
 * Copilot call; `api.<domain>` goes into the profile's hosts.
 */
class GitHubDeviceFlow(
    private val domain: String,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DeviceCodeFlow {
    init {
        require(validDomain(domain)) { "Not github.com or a GHE.com tenant" }
    }

    override val signInHosts: Set<String> = setOf(domain)

    private var deviceCode: String? = null

    override suspend fun start(): DeviceCodePrompt {
        val reply = http.exchange(SignInHttp.form("https://$domain/login/device/code".toHttpUrl(), "client_id" to CLIENT_ID, "scope" to SCOPE))
        if (reply.status !in 200..299) throw SignInHttp.failure(reply)
        val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
        val code = body.string("device_code") ?: throw ProviderException(ProviderError.Malformed)
        val userCode = body.string("user_code")?.trim()?.takeIf { it.length in 1..64 } ?: throw ProviderException(ProviderError.Malformed)
        val expiresIn = body.number("expires_in")?.takeIf { it > 0 } ?: throw ProviderException(ProviderError.Malformed)
        val interval = body.number("interval")?.takeIf { it >= 0 } ?: DEFAULT_INTERVAL_SECONDS
        val page = body.string("verification_uri")?.toHttpUrlOrNull()
        if (page == null || !page.isHttps || page.host != domain || page.encodedPath != "/login/device" || page.username.isNotEmpty() || page.password.isNotEmpty()) {
            throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
        }
        deviceCode = code
        return DeviceCodePrompt(userCode, "https://$domain/login/device", clock() + expiresIn * 1000, maxOf(MIN_INTERVAL_MILLIS, interval * 1000))
    }

    override suspend fun await(prompt: DeviceCodePrompt): SignInResult {
        val code = checkNotNull(deviceCode) { "start() comes first" }
        var interval = prompt.intervalMillis
        while (true) {
            sleep(interval)
            if (clock() >= prompt.expiresAtMillis) throw SignInFailed(SignInFailure.EXPIRED)
            val reply = http.exchange(
                SignInHttp.form(
                    "https://$domain/login/oauth/access_token".toHttpUrl(),
                    "client_id" to CLIENT_ID,
                    "device_code" to code,
                    "grant_type" to DEVICE_GRANT,
                ),
            )
            if (SignInHttp.temporary(reply.status)) throw SignInHttp.failure(reply)
            val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
            body.string("access_token")?.let { token ->
                return SignInResult(Credential.GitHubToken(token, domain), hosts = setOf("api.$domain"))
            }
            when (body.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = maxOf(interval + SLOW_DOWN_MILLIS, (body.number("interval") ?: 0) * 1000)
                "expired_token" -> throw SignInFailed(SignInFailure.EXPIRED)
                "access_denied" -> throw SignInFailed(SignInFailure.REFUSED)
                else -> throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
            }
        }
    }

    override fun toString(): String = "GitHubDeviceFlow(tenant=${domain != PUBLIC})"

    companion object {
        /** GitHub's own OAuth client, which OpenClaw uses: a borrowed identity (design D21). */
        const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
        const val SCOPE = "read:user"
        const val PUBLIC = "github.com"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private val TENANT = Regex("^[a-z0-9-]+\\.ghe\\.com$")
        private const val DEFAULT_INTERVAL_SECONDS = 5L
        private const val MIN_INTERVAL_MILLIS = 1_000L
        private const val SLOW_DOWN_MILLIS = 5_000L

        /** github.com, or a GHE.com tenant such as `acme.ghe.com` (research r27). */
        fun validDomain(domain: String): Boolean = domain == PUBLIC || TENANT.matches(domain)

        /** The tenant that the owner typed, as `acme`, `acme.ghe.com` or `https://acme.ghe.com/`, or null when it is not one. */
        fun tenant(input: String): String? {
            val name = input.trim().lowercase().removePrefix("https://").removeSuffix("/")
            val domain = if (name.endsWith(".ghe.com")) name else "$name.ghe.com"
            return domain.takeIf { TENANT.matches(it) }
        }
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/XaiDeviceFlow.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * xAI's sign-in (design D9, research r31): OIDC discovery at `https://auth.x.ai`, whose device-authorization and token
 * endpoints must be HTTPS on `x.ai` or a subdomain and are leased on the allowlist through [openHosts] until the flow
 * ends; then RFC 8628 with the Grok CLI's client. `verification_uri_complete` is preferred, and must be HTTPS on `x.ai`
 * too. The token answer must hold a refresh token and an expiry (`expires_in`, or the access token's `exp`); the token
 * endpoint's host goes into the profile's hosts.
 */
class XaiDeviceFlow(
    private val http: ProviderHttp,
    private val openHosts: (Set<String>) -> AutoCloseable,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DeviceCodeFlow {
    override val signInHosts: Set<String> = setOf("auth.x.ai")

    private var lease: AutoCloseable? = null
    private var tokenEndpoint: HttpUrl? = null
    private var deviceCode: String? = null

    override suspend fun start(): DeviceCodePrompt {
        val reply = http.exchange(SignInHttp.get(XaiAuth.DISCOVERY_URL.toHttpUrl()))
        if (reply.status !in 200..299) throw SignInHttp.failure(reply)
        val discovery = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
        val device = trusted(discovery.string("device_authorization_endpoint"))
        val token = trusted(discovery.string("token_endpoint"))
        val opened = openHosts(setOf(device.host, token.host))
        lease = opened
        try {
            val answer = http.exchange(SignInHttp.form(device, "client_id" to XaiAuth.CLIENT_ID, "scope" to XaiAuth.SCOPE))
            if (answer.status !in 200..299) throw SignInHttp.failure(answer)
            val body = SignInHttp.parse(answer) ?: throw ProviderException(ProviderError.Malformed)
            val code = body.string("device_code") ?: throw ProviderException(ProviderError.Malformed)
            val userCode = body.string("user_code")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ProviderException(ProviderError.Malformed)
            val page = trusted(body.string("verification_uri_complete") ?: body.string("verification_uri"))
            val expiresIn = body.number("expires_in")?.takeIf { it > 0 } ?: DEFAULT_EXPIRY_SECONDS
            val interval = maxOf(MIN_INTERVAL_MILLIS, (body.number("interval") ?: DEFAULT_INTERVAL_SECONDS) * 1000)
            tokenEndpoint = token
            deviceCode = code
            return DeviceCodePrompt(userCode, page.toString(), clock() + expiresIn * 1000, interval)
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
    }

    override suspend fun await(prompt: DeviceCodePrompt): SignInResult {
        try {
            val endpoint = checkNotNull(tokenEndpoint) { "start() comes first" }
            val code = checkNotNull(deviceCode)
            var interval = prompt.intervalMillis
            while (true) {
                sleep(interval)
                if (clock() >= prompt.expiresAtMillis) throw SignInFailed(SignInFailure.EXPIRED)
                val reply = http.exchange(
                    SignInHttp.form(
                        endpoint,
                        "grant_type" to GitHubDeviceFlow.DEVICE_GRANT,
                        "client_id" to XaiAuth.CLIENT_ID,
                        "device_code" to code,
                    ),
                )
                if (SignInHttp.temporary(reply.status)) throw SignInHttp.failure(reply)
                val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
                if (reply.status in 200..299) {
                    val access = body.string("access_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
                    val refresh = body.string("refresh_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
                    val expiresAt = body.number("expires_in")?.let { clock() + it * 1000 } ?: TokenTimes.jwtExpiry(access)
                        ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
                    val credential = Credential.OAuthToken(access, refresh, expiresAt, tokenEndpoint = endpoint.toString())
                    return SignInResult(credential, hosts = setOf(endpoint.host))
                }
                when (body.string("error")) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += SLOW_DOWN_MILLIS
                    "access_denied", "authorization_denied" -> throw SignInFailed(SignInFailure.REFUSED)
                    "expired_token" -> throw SignInFailed(SignInFailure.EXPIRED)
                    else -> throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
                }
            }
        } finally {
            lease?.close()
        }
    }

    /** An address of xAI's sign-in: HTTPS on `x.ai` or a subdomain, or the flow ends. */
    private fun trusted(address: String?): HttpUrl =
        address?.toHttpUrlOrNull()?.takeIf { it.isHttps && hostIn(it.host, XaiAuth.DOMAIN) } ?: throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)

    override fun toString(): String = "XaiDeviceFlow"

    private companion object {
        const val DEFAULT_EXPIRY_SECONDS = 300L
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val MIN_INTERVAL_MILLIS = 1_000L
        const val SLOW_DOWN_MILLIS = 5_000L
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/MiniMaxDeviceFlow.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * MiniMax's M Plan sign-in (design D9, research r31): MiniMax's user-code grant with PKCE and a `state` that must come back
 * unchanged, through the client that is probably OpenClaw's registration (a borrowed identity, design D21). Polls decide
 * by the body's `status`: `success`, `error`, and anything else pending. MiniMax has no `slow_down`, and research left two
 * units open: an `interval` below 100 is seconds and from 100 on milliseconds, with a floor of 2 s; `expired_in` is read
 * by [TokenTimes.miniMaxTime]. A `resource_url` becomes the base URL only when it is HTTPS on `minimax.io` or
 * `minimaxi.com`; otherwise the region's own Anthropic route is used.
 */
class MiniMaxDeviceFlow(
    private val region: MiniMaxRegion,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DeviceCodeFlow {
    override val signInHosts: Set<String> = setOf(region.accountHost)

    private var verifier: String? = null

    override suspend fun start(): DeviceCodePrompt {
        val pkce = Pkce.verifier()
        val state = Pkce.state()
        val headers = mapOf("Accept" to "application/json", "x-request-id" to UUID.randomUUID().toString())
        val reply = http.exchange(
            SignInHttp.form(
                "https://${region.accountHost}/oauth2/device/code".toHttpUrl(),
                "response_type" to "code",
                "client_id" to MiniMaxAuth.CLIENT_ID,
                "scope" to MiniMaxAuth.SCOPE,
                "code_challenge" to Pkce.challenge(pkce),
                "code_challenge_method" to "S256",
                "state" to state,
                headers = headers,
            ),
        )
        if (reply.status !in 200..299) throw SignInHttp.failure(reply)
        val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
        if (body.string("state") != state) throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
        val userCode = body.string("user_code")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ProviderException(ProviderError.Malformed)
        val page = body.string("verification_uri")?.toHttpUrlOrNull()?.takeIf { it.isHttps && miniMax(it.host) }
            ?: throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
        val now = clock()
        val expiresAt = body.number("expired_in")?.takeIf { it >= 1 }?.let { TokenTimes.miniMaxTime(it, now) }
            ?: throw ProviderException(ProviderError.Malformed)
        verifier = pkce
        return DeviceCodePrompt(userCode, page.toString(), expiresAt, interval(body.number("interval")))
    }

    override suspend fun await(prompt: DeviceCodePrompt): SignInResult {
        val pkce = checkNotNull(verifier) { "start() comes first" }
        while (true) {
            sleep(prompt.intervalMillis)
            val now = clock()
            if (now >= prompt.expiresAtMillis) throw SignInFailed(SignInFailure.EXPIRED)
            val reply = http.exchange(
                SignInHttp.form(
                    region.tokenUrl.toHttpUrl(),
                    "grant_type" to USER_CODE_GRANT,
                    "client_id" to MiniMaxAuth.CLIENT_ID,
                    "user_code" to prompt.userCode,
                    "code_verifier" to pkce,
                ),
            )
            if (SignInHttp.temporary(reply.status)) throw SignInHttp.failure(reply)
            // Design D9 and OpenClaw's poll parser: another refused HTTP status or `status: "error"` ends the sign-in,
            // `success` gives the token, and any other status is pending; only the refresh reads `base_resp`.
            if (reply.status !in 200..299) throw SignInFailed(SignInFailure.REFUSED)
            val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
            when (body.string("status")) {
                "success" -> Unit
                "error" -> throw SignInFailed(SignInFailure.REFUSED)
                else -> continue
            }
            val access = body.string("access_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
            val refresh = body.string("refresh_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
            val expiresAt = body.number("expired_in")?.let { TokenTimes.miniMaxTime(it, clock()) }
            val base = body.string("resource_url")?.toHttpUrlOrNull()?.takeIf { it.isHttps && miniMax(it.host) }?.toString() ?: region.apiBase
            val credential = Credential.OAuthToken(access, refresh, expiresAt, tokenEndpoint = region.tokenUrl, region = region.name.lowercase())
            return SignInResult(credential, baseUrl = base, hosts = setOf(region.accountHost, base.toHttpUrl().host))
        }
    }

    private fun interval(value: Long?): Long {
        val millis = when {
            value == null -> MIN_INTERVAL_MILLIS
            value < 100 -> value * 1000
            else -> value
        }
        return maxOf(MIN_INTERVAL_MILLIS, millis)
    }

    private fun miniMax(host: String): Boolean = hostIn(host, "minimax.io") || hostIn(host, "minimaxi.com")

    override fun toString(): String = "MiniMaxDeviceFlow(region=$region)"

    private companion object {
        const val USER_CODE_GRANT = "urn:ietf:params:oauth:grant-type:user_code"
        const val MIN_INTERVAL_MILLIS = 2_000L
    }
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 63 tests (R₀ + 51 + 12): `GitHubDeviceFlowTest` 4, `XaiDeviceFlowTest` 4, `MiniMaxDeviceFlowTest` 3 and `PkceTest` 1.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`, with `:core:providers` R₀ + 63 and the other modules as before, lint without issues, and no audit finding.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
$main = 'core/providers/src/main/kotlin/dev/kestrel/providers/signin'
$test = 'core/providers/src/test/kotlin/dev/kestrel/providers/signin'
$fix = 'core/providers/src/test/resources/fixtures'
git add "$main/DeviceCode.kt" "$main/Pkce.kt" "$main/GitHubDeviceFlow.kt" "$main/XaiDeviceFlow.kt" "$main/MiniMaxDeviceFlow.kt"
git add "$test/GitHubDeviceFlowTest.kt" "$test/XaiDeviceFlowTest.kt" "$test/MiniMaxDeviceFlowTest.kt" "$test/PkceTest.kt"
git add "$fix/github/device-code.json" "$fix/github/token.json" "$fix/xai/openid-configuration.json" "$fix/xai/device-authorization.json" "$fix/xai/token.json" "$fix/minimax/device-code.json" "$fix/minimax/token.json"
git commit -m "feat(providers): add the device-code sign-ins of Copilot, xAI and MiniMax, and PKCE" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Browser sign-in

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/network/LoopbackServer.kt`
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/signin/Loopback.kt`, `IdTokens.kt`, `ChatGptPlanFlow.kt`, `OpenRouterBrowserFlow.kt`
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/signin/TestIdTokens.kt` and `FakeLoopback.kt` (helpers), `IdTokensTest.kt`, `ChatGptPlanFlowTest.kt`, `OpenRouterBrowserFlowTest.kt`; fixtures `core/providers/src/test/resources/fixtures/chatgpt-plan/openid-configuration.json`, `chatgpt-plan/token.json`; the device test `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/LoopbackServerTest.kt`

**Interfaces:**
- Consumes: Task 1's `ChatGptPlanAuth`, `SignInHttp`, `SignInResult`, `SignInFailed`, `TokenTimes`, `SignInFixtures`; Task 3's `Pkce`; plan 4A's `ChatGptRegistration`, `Credential.OAuthToken` and `ApiKey`, `ApiKeys.acceptable`.
- Produces: in `:core:privacy` (package `dev.kestrel.privacy.network`) `class LoopbackServer(ipv6Loopback: () -> Boolean = ::hasIpv6Loopback)` with `fun open(ipv6: Boolean, port: Int?, path: String): LoopbackServer.Session?`, `class Session : AutoCloseable { val port: Int; val addresses: List<InetAddress>; fun await(expectedState: String, timeoutMillis: Long): Map<String, String>? }` and `companion object { fun hasIpv6Loopback(): Boolean }`. In `:core:providers` (package `dev.kestrel.providers.signin`): `enum class LoopbackAddresses { IPV4, BOTH }`; `interface LoopbackListener { suspend fun open(addresses: LoopbackAddresses, port: Int?, path: String): LoopbackSession? }`; `interface LoopbackSession : AutoCloseable { val port: Int; suspend fun await(expectedState: String, timeoutMillis: Long): LoopbackCallback? }`; `class LoopbackCallback(val query: Map<String, String>)`; `interface BrowserFlow { val signInHosts: Set<String>; suspend fun run(loopback: LoopbackListener, openBrowser: (String) -> Unit): SignInResult }`; `data class IdTokenClaims(val subject: String)`; `object IdTokens { fun keyId(idToken: String): String?; fun verify(idToken: String, keys: JsonObject, issuer: String, audience: String, nonce: String, nowMillis: Long): IdTokenClaims? }`; `class ChatGptPlanFlow(hostId: String, registration: ChatGptRegistration?, consent: Boolean = false, http: ProviderHttp, clock: () -> Long = …) : BrowserFlow` with `companion object { suspend fun revoke(http: ProviderHttp, token: Credential.OAuthToken, pause: suspend (Long) -> Unit = …): Boolean }`; `class OpenRouterBrowserFlow(http: ProviderHttp) : BrowserFlow`.

Design D9, D10 and D22; research r29, r32 and r46; OpenAI's "Registration and sign-in", "Accounts and sessions", "Errors and recovery" and "On your website". `LoopbackServer` is the only new socket code; it lives in `:core:privacy`'s `network` package, the audit's one exception, and `:app` gives it to `:core:providers` as a `LoopbackListener` in Task 6. It binds only the addresses that a redirect names and never the wildcard; it fails closed when a port is taken on any address that it needs, answers other paths with 404 and a wrong `state` with an error page while it keeps waiting, and closes after the callback, its time or Cancel. The ChatGPT plan's flow registers Kestrel on its first sign-in with `dynamic_agent_client`, `agent_name_hint=Kestrel` and the installation's host ID, keeps the issued client ID for retries and later sign-ins, redirects to `http://127.0.0.1:<port>/auth/callback` (1455 when free), checks the discovery document, exchanges the code with PKCE, checks the ID token (RS256 only, the key by `kid` with one more fetch for an unknown one, the issuer, the audience, the expiry with 60 s of skew, the nonce), refuses another account's `sub`, requires the plan scope, and revokes the refresh token on sign-out with two retries. OpenRouter's flow listens on both loopback addresses at `localhost:3000`, or on a free port, and exchanges its code for an API key. Every case of D19's ChatGPT plan list is tested once here, because the plan has no live check (decision 4). Once a callback has issued a client ID, a network failure, a 429 or a 5xx at the exchange or the key-set fetch also ends as `SignInFailed(EXCHANGE_FAILED, kept)`, so that "Try again" never registers Kestrel a second time. The server limits a request's whole head to 2 s, not each read, and a flow that opens no loopback port at all ends as `EXCHANGE_FAILED`, because the browser was never asked.

- [ ] **Step 1: Write the test helpers, the fixtures and the failing tests**

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/TestIdTokens.kt`:

```kotlin
package dev.kestrel.providers.signin

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** A key pair that the tests make, its key set as OpenAI publishes one, and ID tokens signed with it (design D19). */
internal class TestIdTokens(val kid: String = "test-kid") {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** The key set with this key, or with no key when [withKey] is false. */
    fun keySet(withKey: Boolean = true): String {
        if (!withKey) return """{"keys":[]}"""
        val key = pair.public as RSAPublicKey
        return """{"keys":[{"kty":"RSA","kid":"$kid","use":"sig","alg":"RS256","n":"${encode(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())}","e":"${encode(key.publicExponent.toByteArray())}"}]}"""
    }

    /** A token with [claims], signed with this key under [alg] and [headerKid]; a [signature] replaces the real one. */
    fun token(claims: String, alg: String = "RS256", headerKid: String = kid, signature: String? = null): String {
        val head = encode("""{"alg":"$alg","kid":"$headerKid","typ":"JWT"}""".encodeToByteArray())
        val body = encode(claims.encodeToByteArray())
        val signed = signature ?: encode(
            Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update("$head.$body".toByteArray(Charsets.US_ASCII))
                sign()
            },
        )
        return "$head.$body.$signed"
    }

    /** The claims of a valid token for [audience] and [nonce], expiring [expiresInSeconds] after [nowSeconds]. */
    fun claims(audience: String = "\"oaiapp_test\"", nonce: String = "test-nonce", nowSeconds: Long = NOW_SECONDS, expiresInSeconds: Long = 3600, issuer: String = "https://auth.openai.com", subject: String = "test-subject") =
        """{"iss":"$issuer","sub":"$subject","aud":$audience,"nonce":"$nonce","iat":$nowSeconds,"exp":${nowSeconds + expiresInSeconds}}"""

    companion object {
        const val NOW_SECONDS = 1_800_000_000L

        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/FakeLoopback.kt`:

```kotlin
package dev.kestrel.providers.signin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A loopback listener for the JVM tests of the browser flows: [busy] ports are refused, a null port gets port 54321, and
 * the session's callback comes from [answer], which sees the query of the page that the flow opened ([page]). As
 * LoopbackServer does, a callback with another `state` than the one that the flow waits for never arrives.
 */
internal class FakeLoopback : LoopbackListener {
    var busy = emptySet<Int>()
    var page: HttpUrl? = null
    val opened = mutableListOf<Triple<LoopbackAddresses, Int?, String>>()
    var closed = 0
    var answer: (HttpUrl) -> Map<String, String>? = { mapOf("code" to "test-code", "state" to it.queryParameter("state")!!) }

    val open: (String) -> Unit = { page = it.toHttpUrl() }

    override suspend fun open(addresses: LoopbackAddresses, port: Int?, path: String): LoopbackSession? {
        opened += Triple(addresses, port, path)
        if (port in busy) return null
        return object : LoopbackSession {
            override val port: Int = port ?: 54321

            override suspend fun await(expectedState: String, timeoutMillis: Long): LoopbackCallback? {
                val query = answer(checkNotNull(page) { "the flow waits only after it opened the page" }) ?: return null
                // LoopbackServer answers another state with an error page and keeps waiting until its time ends.
                return query.takeIf { it["state"] == expectedState }?.let(::LoopbackCallback)
            }

            override fun close() {
                closed++
            }
        }
    }
}
```

Create `core/providers/src/test/resources/fixtures/chatgpt-plan/openid-configuration.json`:

```json
{
  "issuer": "https://auth.openai.com",
  "authorization_endpoint": "https://auth.openai.com/api/accounts/authorize",
  "token_endpoint": "https://auth.openai.com/api/accounts/oauth/token",
  "jwks_uri": "https://auth.openai.com/.well-known/jwks.json",
  "revocation_endpoint": "https://auth.openai.com/oauth/revoke",
  "response_types_supported": ["code"],
  "id_token_signing_alg_values_supported": ["RS256"]
}
```

Create `core/providers/src/test/resources/fixtures/chatgpt-plan/token.json`:

```json
{
  "access_token": "test-access-1",
  "refresh_token": "test-refresh-1",
  "id_token": "ID_TOKEN",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "chatgpt.tokens.use.direct email offline_access openid profile resource.invoke",
  "earliest_refresh_at": "2027-01-15T08:50:00Z"
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/IdTokensTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The checks of the ChatGPT plan's ID token (design D9 and D19), with a key pair that the test makes. */
class IdTokensTest {
    private val keys = TestIdTokens()
    private val keySet = Json.parseToJsonElement(keys.keySet()) as JsonObject
    private val now = TestIdTokens.NOW_SECONDS * 1000

    private fun verify(token: String, set: JsonObject = keySet) =
        IdTokens.verify(token, set, "https://auth.openai.com", "oaiapp_test", "test-nonce", now)

    @Test
    fun aValidTokenGivesItsSubjectAlsoWithAnAudienceArrayOrAnExpiry30SecondsAgo() {
        assertEquals(IdTokenClaims("test-subject"), verify(keys.token(keys.claims())))
        assertEquals(IdTokenClaims("test-subject"), verify(keys.token(keys.claims(audience = """["other","oaiapp_test"]"""))))
        assertEquals(IdTokenClaims("test-subject"), verify(keys.token(keys.claims(expiresInSeconds = -30))))
        assertEquals("test-kid", IdTokens.keyId(keys.token(keys.claims())))
    }

    @Test
    fun aWrongSignatureIssuerAudienceExpiryOrNonceFails() {
        val other = TestIdTokens()
        assertNull(verify(keys.token(keys.claims(), signature = TestIdTokens.encode(ByteArray(256)))))
        assertNull(verify(other.token(keys.claims())))
        assertNull(verify(keys.token(keys.claims(issuer = "https://auth.example.com"))))
        assertNull(verify(keys.token(keys.claims(audience = "\"oaiapp_other\""))))
        assertNull(verify(keys.token(keys.claims(expiresInSeconds = -120))))
        assertNull(verify(keys.token(keys.claims(nonce = "another-nonce"))))
    }

    @Test
    fun noneHmacAndAnUnknownKidFail() {
        // Signed with the real key, so that only the rule of RS256 refuses it.
        assertNull(verify(keys.token(keys.claims(), alg = "none")))
        assertNull(verify(keys.token(keys.claims(), alg = "HS256")))
        assertNull(verify(keys.token(keys.claims(), headerKid = "unknown-kid")))
        assertNull(verify(keys.token(keys.claims()), Json.parseToJsonElement(keys.keySet(withKey = false)) as JsonObject))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/ChatGptPlanFlowTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ChatGptRegistration
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * The ChatGPT plan's sign-in and sign-out against MockWebServer (design D9 and D19; research r46; OpenAI's "Registration
 * and sign-in", "Accounts and sessions" and "Errors and recovery"). It has no live check (decision 4), so each case of
 * the design is here once.
 */
class ChatGptPlanFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val keys = TestIdTokens()
    private val loopback = FakeLoopback()
    private val now = TestIdTokens.NOW_SECONDS * 1000

    private var discovery = read("chatgpt-plan/openid-configuration.json")
    private val keySets = ArrayDeque<String>()
    private var tokenReply: (Map<String, String>) -> MockResponse = { json(200, tokenBody()) }
    private val revocations = ArrayDeque<MockResponse>()
    private val forms = mutableListOf<Map<String, String>>()

    private fun tokenBody(claims: String = keys.claims(nonce = loopback.page!!.queryParameter("nonce")!!)) =
        read("chatgpt-plan/token.json").replace("ID_TOKEN", keys.token(claims))

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body?.utf8().orEmpty().split('&').filter { it.isNotEmpty() }.associate { field ->
                    val (name, value) = field.split('=', limit = 2)
                    name to URLDecoder.decode(value, Charsets.UTF_8)
                }
                return when (request.url.encodedPath) {
                    "/.well-known/openid-configuration" -> json(200, discovery)
                    "/.well-known/jwks.json" -> json(200, keySets.removeFirstOrNull() ?: keys.keySet())
                    "/api/accounts/oauth/token" -> tokenReply(form.also { forms += it })
                    "/oauth/revoke" -> revocations.removeFirst().also { forms += form }
                    else -> json(404, "{}")
                }
            }
        }
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun flow(registration: ChatGptRegistration? = null, consent: Boolean = false) =
        ChatGptPlanFlow("urn:uuid:00000000-0000-4000-8000-000000000001", registration, consent, http) { now }

    private fun run(flow: ChatGptPlanFlow) = runBlocking { flow.run(loopback, loopback.open) }

    private fun failureOf(flow: ChatGptPlanFlow): SignInFailed =
        try {
            run(flow)
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e
        }

    private fun issuedCallback(clientId: String?): (okhttp3.HttpUrl) -> Map<String, String> = { page ->
        listOfNotNull("code" to "test-code", "state" to page.queryParameter("state")!!, clientId?.let { "client_id" to it }).toMap()
    }

    @Test
    fun theFirstRegistrationSendsTheDynamicClientTheNameAndTheHostIdAndKeepsTheIssuedClientId() {
        loopback.answer = issuedCallback("oaiapp_test")

        val result = run(flow())

        val page = loopback.page!!
        assertEquals("https://auth.openai.com/api/accounts/authorize", page.newBuilder().query(null).build().toString())
        assertEquals("dynamic_agent_client", page.queryParameter("client_id"))
        assertEquals("Kestrel", page.queryParameter("agent_name_hint"))
        assertEquals("urn:uuid:00000000-0000-4000-8000-000000000001", page.queryParameter("ext_agent_host_id"))
        assertEquals("code", page.queryParameter("response_type"))
        assertEquals("http://127.0.0.1:1455/auth/callback", page.queryParameter("redirect_uri"))
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", page.queryParameter("scope"))
        assertEquals("https://api.openai.com/v1", page.queryParameter("resource"))
        assertEquals("S256", page.queryParameter("code_challenge_method"))
        assertNull(page.queryParameter("prompt"))
        assertNull(page.queryParameter("id_token_hint"))
        assertNull(page.queryParameter("login_hint"))
        assertEquals(listOf(Triple(LoopbackAddresses.IPV4, 1455 as Int?, "/auth/callback")), loopback.opened)
        assertEquals(1, loopback.closed)
        val exchange = forms.single()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("oaiapp_test", exchange["client_id"])
        assertEquals("test-code", exchange["code"])
        assertEquals("http://127.0.0.1:1455/auth/callback", exchange["redirect_uri"])
        assertEquals("https://api.openai.com/v1", exchange["resource"])
        assertEquals(page.queryParameter("code_challenge"), Pkce.challenge(exchange.getValue("code_verifier")))
        assertEquals(
            Credential.OAuthToken("test-access-1", "test-refresh-1", now + 3_600_000, 1_800_003_000_000L, clientId = "oaiapp_test"),
            result.credential,
        )
        assertEquals(ChatGptRegistration("oaiapp_test", "test-subject"), result.registration)
        assertEquals(setOf("api.openai.com", "auth.openai.com"), result.hosts)
    }

    @Test
    fun aBusyPort1455TakesAFreePortWithTheSamePath() {
        loopback.busy = setOf(1455)
        loopback.answer = issuedCallback("oaiapp_test")

        run(flow())

        assertEquals("http://127.0.0.1:54321/auth/callback", loopback.page!!.queryParameter("redirect_uri"))
        assertEquals("http://127.0.0.1:54321/auth/callback", forms.single()["redirect_uri"])
    }

    @Test
    fun aFirstCallbackWithoutAnIssuedClientIdOrWithTheDynamicOneIsAnIncompleteRegistration() {
        loopback.answer = issuedCallback(null)
        assertEquals(SignInFailure.REGISTRATION_INCOMPLETE, failureOf(flow()).kind)
        loopback.answer = issuedCallback("dynamic_agent_client")
        assertEquals(SignInFailure.REGISTRATION_INCOMPLETE, failureOf(flow()).kind)
        assertTrue(forms.isEmpty())
    }

    @Test
    fun aReturningCallbackMayOmitTheClientIdButNeverNameAnother() {
        val kept = ChatGptRegistration("oaiapp_test", "test-subject")
        loopback.answer = issuedCallback(null)

        val result = run(flow(kept))

        assertEquals("oaiapp_test", loopback.page!!.queryParameter("client_id"))
        assertNull(loopback.page!!.queryParameter("agent_name_hint"))
        assertEquals("oaiapp_test", (result.credential as Credential.OAuthToken).clientId)
        loopback.answer = issuedCallback("oaiapp_other")
        assertEquals(SignInFailure.OTHER_ACCOUNT, failureOf(flow(kept)).kind)
    }

    @Test
    fun accessDeniedIsACancelAndAnotherErrorOrARefusedClientRefusesTheRegistration() {
        loopback.answer = { mapOf("error" to "access_denied", "state" to it.queryParameter("state")!!) }
        assertEquals(SignInFailure.CANCELLED, failureOf(flow()).kind)
        loopback.answer = { mapOf("error" to "unauthorized_client", "state" to it.queryParameter("state")!!) }
        assertEquals(SignInFailure.REGISTRATION_REFUSED, failureOf(flow()).kind)
        loopback.answer = issuedCallback("oaiapp_test")
        tokenReply = { json(401, """{"error":"invalid_client"}""") }
        assertEquals(SignInFailure.REGISTRATION_REFUSED, failureOf(flow()).kind)
    }

    @Test
    fun invalidGrantAtTheExchangeKeepsTheIssuedClientIdForTryAgain() {
        loopback.answer = issuedCallback("oaiapp_test")
        tokenReply = { json(400, """{"error":"invalid_grant"}""") }

        val failed = failureOf(flow())

        assertEquals(SignInFailure.EXCHANGE_FAILED, failed.kind)
        assertEquals(ChatGptRegistration("oaiapp_test", null), failed.registration)
        // A 503 at the exchange keeps the issued client ID too, so that "Try again" never registers Kestrel again.
        tokenReply = { json(503, "{}") }
        val unavailable = failureOf(flow())
        assertEquals(SignInFailure.EXCHANGE_FAILED, unavailable.kind)
        assertEquals(ChatGptRegistration("oaiapp_test", null), unavailable.registration)
    }

    @Test
    fun aDiscoveryDocumentWithAForeignKeySetOrRevocationAddressIsRefused() {
        val original = discovery
        discovery = original.replace("https://auth.openai.com/.well-known/jwks.json", "https://keys.example.com/jwks.json")
        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf(flow()).kind)
        discovery = original.replace("https://auth.openai.com/oauth/revoke", "http://auth.openai.com/oauth/revoke")
        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf(flow()).kind)
        assertNull(loopback.page)
    }

    @Test
    fun aMissingPlanScopeKeepsTheRegistrationAndConsentAsksForItAgain() {
        loopback.answer = issuedCallback("oaiapp_test")
        tokenReply = { json(200, tokenBody().replace("chatgpt.tokens.use.direct ", "")) }

        val failed = failureOf(flow())
        assertEquals(SignInFailure.PLAN_USE_NOT_ALLOWED, failed.kind)
        assertEquals(ChatGptRegistration("oaiapp_test", "test-subject"), failed.registration)

        tokenReply = { json(200, tokenBody()) }
        loopback.answer = issuedCallback(null)
        run(flow(failed.registration, consent = true))
        assertEquals("consent", loopback.page!!.queryParameter("prompt"))
    }

    @Test
    fun anotherAccountsSubjectReplacesNothing() {
        loopback.answer = issuedCallback(null)
        tokenReply = { json(200, tokenBody(keys.claims(nonce = loopback.page!!.queryParameter("nonce")!!, subject = "another-subject"))) }

        val failed = failureOf(flow(ChatGptRegistration("oaiapp_test", "test-subject")))

        assertEquals(SignInFailure.OTHER_ACCOUNT, failed.kind)
        assertNull(failed.registration)
    }

    @Test
    fun anUnknownKidIsFetchedOnceMoreAndAKidStillUnknownFails() {
        loopback.answer = issuedCallback("oaiapp_test")
        keySets += keys.keySet(withKey = false)
        run(flow())

        keySets += keys.keySet(withKey = false)
        keySets += keys.keySet(withKey = false)
        val failed = failureOf(flow())

        assertEquals(SignInFailure.EXCHANGE_FAILED, failed.kind)
        assertEquals(4, calls.requests.count { it.encodedPath == "/.well-known/jwks.json" })
    }

    @Test
    fun theSignOutRevokesTheRefreshTokenWithTwoRetriesAndSaysWhenOpenAiDidNotConfirm() = runBlocking {
        val token = Credential.OAuthToken("test-access-1", "test-refresh-1", null, clientId = "oaiapp_test")
        val pauses = mutableListOf<Long>()
        revocations += json(503, "{}")
        revocations += json(502, "{}")
        revocations += MockResponse.Builder().code(200).build()

        assertTrue(ChatGptPlanFlow.revoke(http, token) { pauses += it })
        assertEquals(listOf(1_000L, 3_000L), pauses)
        assertEquals(mapOf("token" to "test-refresh-1", "token_type_hint" to "refresh_token", "client_id" to "oaiapp_test"), forms.last())

        repeat(3) { revocations += json(503, "{}") }
        assertFalse(ChatGptPlanFlow.revoke(http, token) {})
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/signin/OpenRouterBrowserFlowTest.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** OpenRouter's PKCE sign-in with a `localhost` redirect (design D9 and D10; research r32). */
class OpenRouterBrowserFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val loopback = FakeLoopback()

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
        // OpenRouter puts the state into the callback address that it was given.
        loopback.answer = { page -> mapOf("code" to "test-code", "state" to page.queryParameter("callback_url")!!.toHttpUrl().queryParameter("state")!!) }
    }

    @After
    fun stop() {
        server.close()
    }

    private fun failureOf(block: suspend () -> Unit): SignInFailure =
        try {
            runBlocking { block() }
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e.kind
        }

    @Test
    fun theSignInListensOnBothLoopbackAddressesOnPort3000AndExchangesTheCodeForAKey() = runBlocking {
        server.enqueue(json(200, """{"key":"sk-or-test-key","user_id":"test-user"}"""))

        val result = OpenRouterBrowserFlow(http).run(loopback, loopback.open)

        val page = loopback.page!!
        assertEquals("https://openrouter.ai/auth", page.newBuilder().query(null).build().toString())
        val callback = page.queryParameter("callback_url")!!.toHttpUrl()
        assertEquals("http://localhost:3000/openrouter-oauth/callback", callback.newBuilder().query(null).build().toString())
        assertEquals("S256", page.queryParameter("code_challenge_method"))
        assertEquals(listOf(Triple(LoopbackAddresses.BOTH, 3000 as Int?, "/openrouter-oauth/callback")), loopback.opened)
        assertEquals(Credential.ApiKey("sk-or-test-key"), result.credential)
        assertEquals("https://openrouter.ai/api/v1/auth/keys", calls.requests.single().toString())
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertEquals("test-code", body["code"]!!.jsonPrimitive.content)
        assertEquals("S256", body["code_challenge_method"]!!.jsonPrimitive.content)
        assertEquals(page.queryParameter("code_challenge"), Pkce.challenge(body["code_verifier"]!!.jsonPrimitive.content))
    }

    @Test
    fun aBusyPort3000TakesAFreePort() = runBlocking {
        loopback.busy = setOf(3000)
        server.enqueue(json(200, """{"key":"sk-or-test-key"}"""))

        OpenRouterBrowserFlow(http).run(loopback, loopback.open)

        assertEquals("localhost:54321", loopback.page!!.queryParameter("callback_url")!!.toHttpUrl().let { "${it.host}:${it.port}" })
    }

    @Test
    fun aRefusedCodeOrNoCallbackEndsTheSignIn() {
        server.enqueue(json(403, """{"error":{"message":"invalid code"}}"""))
        assertEquals(SignInFailure.EXCHANGE_FAILED, failureOf { OpenRouterBrowserFlow(http).run(loopback, loopback.open) })

        loopback.answer = { null }
        assertEquals(SignInFailure.NO_CALLBACK, failureOf { OpenRouterBrowserFlow(http).run(loopback, loopback.open) })
    }
}
```

Create `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/LoopbackServerTest.kt`:

```kotlin
package dev.kestrel.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.privacy.network.LoopbackServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The loopback server of browser sign-in on the device (design D10 and D19): which addresses it binds, how it reports a
 * busy port, and how it answers. Every server that a test opens is closed at its end.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackServerTest {
    private val ipv4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
    private val held = mutableListOf<AutoCloseable>()
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun closeAll() {
        held.forEach { it.close() }
        executor.shutdownNow()
    }

    private fun <T : AutoCloseable> T.held(): T = also { held += it }

    /** Sends a GET of [target] to [address]:[port] and returns the status line, or null when nothing listens. */
    private fun get(address: InetAddress, port: Int, target: String): String? =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), 2_000)
                socket.soTimeout = 5_000
                socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost:$port\r\n\r\n".encodeToByteArray())
                socket.getInputStream().bufferedReader().readText().substringBefore("\r\n")
            }
        } catch (e: IOException) {
            null
        }

    @Test
    fun theChatGptPlansRedirectBindsOnly127001AndNeverTheWildcard() {
        val session = opened(LoopbackServer().open(ipv6 = false, port = 1455, path = "/auth/callback")).held()

        assertEquals(1455, session.port)
        assertEquals(listOf(ipv4), session.addresses)
        assertTrue(session.addresses.none { it.isAnyLocalAddress })
        if (LoopbackServer.hasIpv6Loopback()) assertNull(get(ipv6, 1455, "/auth/callback"))
    }

    @Test
    fun openRoutersRedirectBindsBothLoopbackAddressesOnPort3000() {
        assumeTrue("The device has no IPv6 loopback", LoopbackServer.hasIpv6Loopback())

        val session = opened(LoopbackServer().open(ipv6 = true, port = 3000, path = "/openrouter-oauth/callback")).held()

        assertEquals(3000, session.port)
        assertEquals(listOf(ipv4, ipv6), session.addresses)
        assertTrue(session.addresses.none { it.isAnyLocalAddress })
    }

    @Test
    fun aPortHeldOnIpv6OnlyIsBusyForALocalhostRedirectButNotFor127001() {
        assumeTrue("The device has no IPv6 loopback", LoopbackServer.hasIpv6Loopback())
        ServerSocket().apply { bind(InetSocketAddress(ipv6, 3000)) }.held()

        assertNull(LoopbackServer().open(ipv6 = true, port = 3000, path = "/openrouter-oauth/callback"))
        opened(LoopbackServer().open(ipv6 = false, port = 3000, path = "/auth/callback")).held()
    }

    @Test
    fun aBusyPortIsReportedAndANullPortFindsAFreeOne() {
        ServerSocket().apply { bind(InetSocketAddress(ipv4, 1456)) }.held()

        assertNull(LoopbackServer().open(ipv6 = false, port = 1456, path = "/auth/callback"))
        val free = opened(LoopbackServer().open(ipv6 = true, port = null, path = "/auth/callback")).held()
        assertTrue(free.port > 0)
    }

    @Test
    fun otherPathsGet404AWrongStateIsAnsweredAndTheServerKeepsWaitingUntilTheCallback() {
        val session = opened(LoopbackServer().open(ipv6 = false, port = null, path = "/auth/callback")).held()
        val result = executor.submit<Map<String, String>?> { session.await("test-state", 20_000) }

        assertEquals("HTTP/1.1 404 Not Found", get(ipv4, session.port, "/favicon.ico"))
        assertEquals("HTTP/1.1 400 Bad Request", get(ipv4, session.port, "/auth/callback?code=test-code&state=wrong"))
        assertEquals("HTTP/1.1 400 Bad Request", get(ipv4, session.port, "/auth/callback?code=test-code"))
        assertEquals("HTTP/1.1 200 OK", get(ipv4, session.port, "/auth/callback?code=test%2Bcode&state=test-state"))

        assertEquals(mapOf("code" to "test+code", "state" to "test-state"), result.get(5, TimeUnit.SECONDS))
        // The server closed after the callback.
        assertNull(get(ipv4, session.port, "/auth/callback"))
    }

    @Test
    fun theServerClosesAfterItsTimeAndOnClose() {
        val timed = opened(LoopbackServer().open(ipv6 = false, port = null, path = "/auth/callback")).held()
        assertNull(timed.await("test-state", 300))
        assertNull(get(ipv4, timed.port, "/auth/callback"))

        val cancelled = opened(LoopbackServer().open(ipv6 = false, port = null, path = "/auth/callback")).held()
        val result = executor.submit<Map<String, String>?> { cancelled.await("test-state", 60_000) }
        Thread.sleep(300)
        cancelled.close()
        assertNull(result.get(5, TimeUnit.SECONDS))
    }

    private fun opened(session: LoopbackServer.Session?): LoopbackServer.Session {
        org.junit.Assert.assertNotNull("the server did not open", session)
        return session!!
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'LoopbackListener'", "Unresolved reference 'IdTokens'", "Unresolved reference 'ChatGptPlanFlow'" and "Unresolved reference 'OpenRouterBrowserFlow'".

- [ ] **Step 3: Write the loopback server**

Create `core/privacy/src/main/kotlin/dev/kestrel/privacy/network/LoopbackServer.kt`:

```kotlin
package dev.kestrel.privacy.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * The server of a browser sign-in's loopback redirect (design D10; RFC 8252): it lives in this package, the audit's only
 * exception, because it opens sockets. It binds only the addresses that the redirect names, never the wildcard address:
 * `127.0.0.1`, and `::1` too for a `localhost` redirect, because Chrome tries `::1` first. It fails closed: a port that is
 * taken on any address that it needs is reported as busy (null), so that a redirect never reaches another app's server;
 * it goes on with `127.0.0.1` alone only when the device has no IPv6 loopback. A null port means a free port.
 */
class LoopbackServer(private val ipv6Loopback: () -> Boolean = ::hasIpv6Loopback) {
    fun open(ipv6: Boolean, port: Int?, path: String): Session? {
        val both = ipv6 && ipv6Loopback()
        repeat(if (port == null) FREE_PORT_TRIES else 1) {
            val first = bind(IPV4, port ?: 0) ?: return null
            if (!both) return Session(listOf(first), path)
            val second = bind(IPV6, first.localPort)
            if (second != null) return Session(listOf(first, second), path)
            first.close()
            if (port != null) return null
        }
        return null
    }

    /**
     * One listening server. [await] blocks, so callers run it on Dispatchers.IO. It accepts only a `GET` of the exact path
     * and reads at most 8 KB of a request; other paths, such as Chrome's `/favicon.ico`, get 404; a request on the path with
     * a wrong or missing `state` gets a short error page, and the server keeps waiting; the callback with the right state
     * gets a short page without outside resources, and its query is returned. The server closes after that callback,
     * after [await]'s time, or on [close].
     */
    class Session internal constructor(private val sockets: List<ServerSocket>, private val path: String) : AutoCloseable {
        val port: Int = sockets.first().localPort

        /** The addresses that the server listens on, for the device test that checks that it never binds the wildcard. */
        val addresses: List<InetAddress> = sockets.map { it.inetAddress }

        @Volatile
        private var closed = false

        fun await(expectedState: String, timeoutMillis: Long): Map<String, String>? {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            try {
                sockets.forEach { it.soTimeout = ACCEPT_SLICE_MILLIS }
                while (!closed && System.nanoTime() < deadline) {
                    for (socket in sockets) {
                        val client = try {
                            socket.accept()
                        } catch (e: SocketTimeoutException) {
                            continue
                        } catch (e: IOException) {
                            if (closed) return null
                            // Such as no file descriptors left: wait a slice instead of trying again at once.
                            Thread.sleep(ACCEPT_SLICE_MILLIS.toLong())
                            continue
                        }
                        val query = try {
                            serve(client, expectedState)
                        } finally {
                            try {
                                client.close()
                            } catch (e: IOException) {
                                // A failed close does not end the session, also not after a good callback.
                            }
                        }
                        query?.let { return it }
                    }
                }
                return null
            } catch (e: IOException) {
                return null
            } finally {
                close()
            }
        }

        override fun close() {
            closed = true
            sockets.forEach {
                try {
                    it.close()
                } catch (e: IOException) {
                    // Already closed.
                }
            }
        }

        /** Answers one request, and returns the callback's query when it carries [expectedState]. */
        private fun serve(client: Socket, expectedState: String): Map<String, String>? {
            val line = try {
                readRequestLine(client)
            } catch (e: IOException) {
                null
            }
            val parts = line?.split(' ')
            if (parts == null || parts.size != 3 || parts[0] != "GET") {
                respond(client, 400, "Bad Request", PAGE_REFUSED)
                return null
            }
            val target = parts[1]
            if (target.substringBefore('?') != path) {
                respond(client, 404, "Not Found", PAGE_NOT_FOUND)
                return null
            }
            val query = parseQuery(target.substringAfter('?', ""))
            if (query["state"] != expectedState) {
                respond(client, 400, "Bad Request", PAGE_REFUSED)
                return null
            }
            respond(client, 200, "OK", PAGE_RECEIVED)
            return query
        }

        private fun readRequestLine(client: Socket): String? {
            val input = client.getInputStream()
            val head = ByteArrayOutputStream()
            // One limit for the whole head, not for each read, so that a client that sends a byte now and then cannot hold
            // the server past its time or Cancel.
            val until = System.nanoTime() + READ_TIMEOUT_MILLIS * 1_000_000L
            while (head.size() < MAX_REQUEST_BYTES) {
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0 || closed) throw SocketTimeoutException()
                client.soTimeout = left.toInt()
                val byte = input.read()
                if (byte < 0) break
                head.write(byte)
                val bytes = head.toByteArray()
                val size = bytes.size
                if (size >= 4 && bytes[size - 4] == CR && bytes[size - 3] == LF && bytes[size - 2] == CR && bytes[size - 1] == LF) break
            }
            return head.toByteArray().decodeToString().substringBefore("\r\n").takeIf { it.isNotEmpty() }
        }

        private fun parseQuery(query: String): Map<String, String> {
            val fields = linkedMapOf<String, String>()
            for (field in query.split('&')) {
                if (field.isEmpty()) continue
                val name = decode(field.substringBefore('=')) ?: continue
                val value = decode(field.substringAfter('=', "")) ?: continue
                fields.putIfAbsent(name, value)
            }
            return fields
        }

        private fun decode(text: String): String? =
            try {
                URLDecoder.decode(text, Charsets.UTF_8.name())
            } catch (e: IllegalArgumentException) {
                null
            }

        private fun respond(client: Socket, code: Int, reason: String, page: String) {
            val body = page.encodeToByteArray()
            val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            try {
                client.getOutputStream().run {
                    write(head.encodeToByteArray())
                    write(body)
                    flush()
                }
            } catch (e: IOException) {
                // The browser went away; the server keeps waiting or returns the callback all the same.
            }
        }
    }

    companion object {
        private val IPV4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        private val IPV6: InetAddress = InetAddress.getByAddress("localhost", ByteArray(16).also { it[15] = 1 })
        private const val FREE_PORT_TRIES = 10
        private const val ACCEPT_SLICE_MILLIS = 200
        private const val READ_TIMEOUT_MILLIS = 2_000
        private const val MAX_REQUEST_BYTES = 8 * 1024
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val PAGE_RECEIVED =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Kestrel</title></head><body>" +
                "<p>Kestrel received the sign-in. You can close this tab.</p></body></html>"
        private const val PAGE_REFUSED =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Kestrel</title></head><body>" +
                "<p>This is not the sign-in that Kestrel is waiting for. Start the sign-in again in Kestrel.</p></body></html>"
        private const val PAGE_NOT_FOUND = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Kestrel</title></head><body></body></html>"

        private fun bind(address: InetAddress, port: Int): ServerSocket? {
            val socket = ServerSocket()
            return try {
                socket.bind(InetSocketAddress(address, port), BACKLOG)
                socket
            } catch (e: IOException) {
                socket.close()
                null
            }
        }

        private const val BACKLOG = 8

        /** Whether the device has an IPv6 loopback address that a server can bind. */
        fun hasIpv6Loopback(): Boolean = bind(IPV6, 0)?.let { it.close(); true } ?: false
    }
}
```

- [ ] **Step 4: Write the loopback types and the ID-token check**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/Loopback.kt`:

```kotlin
package dev.kestrel.providers.signin

/** The loopback addresses that a redirect names (design D10). */
enum class LoopbackAddresses {
    /** `http://127.0.0.1:<port>/…`: the ChatGPT plan. */
    IPV4,

    /** `http://localhost:<port>/…`, which Chrome tries on `::1` first: OpenRouter. */
    BOTH,
}

/**
 * The loopback server of a browser sign-in, which `:app` gives over `:core:privacy`'s LoopbackServer, because only the
 * privacy module may open a socket (design D1 and D10). [open] returns null when the port is busy on any address that it
 * needs; a null [port] means a free port.
 */
interface LoopbackListener {
    suspend fun open(addresses: LoopbackAddresses, port: Int?, path: String): LoopbackSession?
}

/**
 * One listening server. [await] waits for a `GET` of the exact path with [expectedState] and returns its query, or null
 * after [timeoutMillis] or on close; a request with another state gets an error page, and the server keeps waiting.
 */
interface LoopbackSession : AutoCloseable {
    val port: Int

    suspend fun await(expectedState: String, timeoutMillis: Long): LoopbackCallback?
}

/** The callback's query, which holds the authorization code and the state, so toString prints only the field names. */
class LoopbackCallback(val query: Map<String, String>) {
    override fun toString(): String = "LoopbackCallback(fields=${query.keys.sorted()})"
}

/**
 * A sign-in in the owner's browser with a loopback redirect (design D9 and D10). [run] opens the listener before it calls
 * [openBrowser] with the sign-in page's address, and waits at most 5 minutes for the callback. [signInHosts] are the hosts
 * of Kestrel's own requests while it runs; the caller leases them on the allowlist.
 */
interface BrowserFlow {
    val signInHosts: Set<String>

    suspend fun run(loopback: LoopbackListener, openBrowser: (String) -> Unit): SignInResult
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/IdTokens.kt`:

```kotlin
package dev.kestrel.providers.signin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** The claims of a checked ID token that Kestrel keeps: the account's opaque `sub`, which toString never prints. */
data class IdTokenClaims(val subject: String) {
    override fun toString(): String = "IdTokenClaims(subject=set)"
}

/**
 * Checks the ChatGPT plan's ID token (design D9; OpenAI's "Registration and sign-in", whose signature example on "On
 * your website" uses OpenAI's published key set with the `jose` library and names no algorithm). Kestrel accepts only
 * RS256 with the RSA key whose `kid` matches the token's header, and refuses `none`, every HMAC algorithm and any other.
 */
object IdTokens {
    private const val CLOCK_SKEW_MILLIS = 60_000L

    /** The `kid` of the token's header, or null; the caller fetches the key set once more when it is not in the set. */
    fun keyId(idToken: String): String? = part(idToken, 0)?.string("kid")

    /**
     * The claims, or null when any check fails: the signature, `iss` equal to [issuer], `aud` (a string or an array) that
     * holds [audience], `exp` in the future with 60 s allowed for clock difference, `nonce` equal to [nonce], and a `sub`.
     */
    fun verify(idToken: String, keys: JsonObject, issuer: String, audience: String, nonce: String, nowMillis: Long): IdTokenClaims? {
        val parts = idToken.split('.')
        if (parts.size != 3) return null
        val header = part(idToken, 0) ?: return null
        if (header.string("alg") != "RS256") return null
        val kid = header.string("kid")
        val key = (keys["keys"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull {
            it.string("kid") == kid && it.string("kty") == "RSA" && it.string("alg").let { alg -> alg == null || alg == "RS256" } &&
                it.string("use").let { use -> use == null || use == "sig" }
        } ?: return null
        if (!signed(parts, key)) return null
        val claims = part(idToken, 1) ?: return null
        if (claims.string("iss") != issuer) return null
        val aud = claims["aud"]
        val audienceOk = (aud as? JsonPrimitive)?.takeIf { it.isString }?.content == audience ||
            (aud as? JsonArray)?.any { (it as? JsonPrimitive)?.content == audience } == true
        if (!audienceOk) return null
        val expiry = (claims["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        if ((expiry * 1000).toLong() + CLOCK_SKEW_MILLIS <= nowMillis) return null
        if (claims.string("nonce") != nonce) return null
        val subject = claims.string("sub")?.takeIf { it.isNotEmpty() } ?: return null
        return IdTokenClaims(subject)
    }

    private fun signed(parts: List<String>, jwk: JsonObject): Boolean =
        try {
            val modulus = BigInteger(1, Base64.getUrlDecoder().decode(jwk.string("n") ?: return false))
            val exponent = BigInteger(1, Base64.getUrlDecoder().decode(jwk.string("e") ?: return false))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(Base64.getUrlDecoder().decode(parts[2]))
            }
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: GeneralSecurityException) {
            false
        }

    private fun part(token: String, index: Int): JsonObject? {
        val text = token.split('.').getOrNull(index) ?: return null
        return try {
            Json.parseToJsonElement(Base64.getUrlDecoder().decode(text).decodeToString()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
```

- [ ] **Step 5: Write the two browser flows**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/ChatGptPlanFlow.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ChatGptRegistration
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The ChatGPT plan's sign-in: OpenAI's "Sign in with ChatGPT" for open-source apps, which registers Kestrel as its own app
 * (design D9; research r46; OpenAI's "Registration and sign-in", "Token reference", "Accounts and sessions" and "Errors
 * and recovery"). It borrows nothing.
 *
 * A first sign-in ([registration] null) sends `client_id=dynamic_agent_client` with `agent_name_hint=Kestrel` and the
 * installation's [hostId], and its callback must return the issued client ID; a returning sign-in sends the kept issued
 * ID, and its callback may omit it but never name another. The redirect is `http://127.0.0.1:<port>/auth/callback`, on
 * port 1455 when it is free, never `localhost`. The flow checks the discovery document, exchanges the code with PKCE,
 * checks the ID token, refuses another account's `sub`, and requires the plan scope `chatgpt.tokens.use.direct`; without
 * it the registration is kept and the owner may allow plan use again ([consent] adds `prompt=consent`). Kestrel keeps no
 * ID token and no e-mail address, so it sends neither `id_token_hint` nor `login_hint`.
 */
class ChatGptPlanFlow(
    private val hostId: String,
    private val registration: ChatGptRegistration?,
    private val consent: Boolean = false,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
) : BrowserFlow {
    override val signInHosts: Set<String> = setOf(ChatGptPlanAuth.HOST)

    override suspend fun run(loopback: LoopbackListener, openBrowser: (String) -> Unit): SignInResult {
        val discovery = discover(http)
        val session = loopback.open(LoopbackAddresses.IPV4, ChatGptPlanAuth.PREFERRED_PORT, ChatGptPlanAuth.CALLBACK_PATH)
            ?: loopback.open(LoopbackAddresses.IPV4, null, ChatGptPlanAuth.CALLBACK_PATH)
            // No loopback port opened, so the browser was never asked: the sign-in did not finish.
            ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, registration)
        val redirect = "http://127.0.0.1:${session.port}${ChatGptPlanAuth.CALLBACK_PATH}"
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val nonce = Pkce.nonce()
        val callback = session.use {
            openBrowser(authorizeUrl(redirect, Pkce.challenge(verifier), state, nonce).toString())
            session.await(state, CALLBACK_WAIT_MILLIS) ?: throw SignInFailed(SignInFailure.NO_CALLBACK)
        }.query
        callback["error"]?.let { throw SignInFailed(if (it == "access_denied") SignInFailure.CANCELLED else SignInFailure.REGISTRATION_REFUSED) }
        val returned = callback["client_id"]
        val clientId = if (registration == null) {
            returned?.takeIf { it.isNotEmpty() && it != ChatGptPlanAuth.DYNAMIC_CLIENT } ?: throw SignInFailed(SignInFailure.REGISTRATION_INCOMPLETE)
        } else {
            if (returned != null && returned != registration.clientId) throw SignInFailed(SignInFailure.OTHER_ACCOUNT)
            registration.clientId
        }
        // A retry in the same add flow reuses the issued client ID and never registers Kestrel a second time.
        val kept = ChatGptRegistration(clientId, registration?.subject)
        try {
            val code = callback["code"] ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            val reply = http.exchange(
                SignInHttp.form(
                    ChatGptPlanAuth.TOKEN_URL.toHttpUrl(),
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirect,
                    "resource" to ChatGptPlanAuth.RESOURCE,
                ),
            )
            if (SignInHttp.temporary(reply.status)) throw SignInHttp.failure(reply)
            val body = SignInHttp.parse(reply)
            if (reply.status !in 200..299) {
                throw when (body?.string("error")) {
                    "invalid_client", "unauthorized_client" -> SignInFailed(SignInFailure.REGISTRATION_REFUSED)
                    else -> SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
                }
            }
            val access = body?.string("access_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            val refresh = body.string("refresh_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            val idToken = body.string("id_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            val claims = checkIdToken(idToken, discovery, clientId, nonce) ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            if (registration?.subject != null && registration.subject != claims.subject) throw SignInFailed(SignInFailure.OTHER_ACCOUNT)
            val registered = ChatGptRegistration(clientId, claims.subject)
            // RFC 6749 lets the token answer leave out a scope that equals the request's, and the callback carries it too.
            val scope = body.string("scope") ?: callback["scope"]
            if (ChatGptPlanAuth.PLAN_SCOPE !in scope.orEmpty().split(' ')) {
                throw SignInFailed(SignInFailure.PLAN_USE_NOT_ALLOWED, registered)
            }
            val now = clock()
            val credential = Credential.OAuthToken(
                access = access,
                refresh = refresh,
                expiresAtMillis = body.number("expires_in")?.let { now + it * 1000 },
                earliestRefreshAtMillis = TokenTimes.earliestRefreshAt(body["earliest_refresh_at"]),
                clientId = clientId,
            )
            return SignInResult(credential, hosts = setOf(ChatGptPlanAuth.API_HOST, ChatGptPlanAuth.HOST), registration = registered)
        } catch (e: ProviderException) {
            // No network, a 429 or a 5xx at the exchange or the key set: "Try again" must reuse the issued client ID (design D9).
            throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
        }
    }

    private fun authorizeUrl(redirect: String, challenge: String, state: String, nonce: String): HttpUrl =
        ChatGptPlanAuth.AUTHORIZE_URL.toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", registration?.clientId ?: ChatGptPlanAuth.DYNAMIC_CLIENT)
            if (registration == null) addQueryParameter("agent_name_hint", ChatGptPlanAuth.AGENT_NAME)
            addQueryParameter("ext_agent_host_id", hostId)
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", redirect)
            addQueryParameter("scope", ChatGptPlanAuth.SCOPE)
            addQueryParameter("resource", ChatGptPlanAuth.RESOURCE)
            addQueryParameter("state", state)
            addQueryParameter("nonce", nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", challenge)
            if (consent) addQueryParameter("prompt", "consent")
        }.build()

    /** The ID token's claims with OpenAI's key set, fetched once more when the token's `kid` is not in it. */
    private suspend fun checkIdToken(idToken: String, discovery: Discovery, clientId: String, nonce: String): IdTokenClaims? {
        var keys = keySet(discovery.jwks)
        IdTokens.verify(idToken, keys, discovery.issuer, clientId, nonce, clock())?.let { return it }
        if (IdTokens.keyId(idToken) in kids(keys)) return null
        keys = keySet(discovery.jwks)
        return IdTokens.verify(idToken, keys, discovery.issuer, clientId, nonce, clock())
    }

    private suspend fun keySet(url: HttpUrl): JsonObject {
        val reply = http.exchange(SignInHttp.get(url))
        if (reply.status !in 200..299) throw SignInHttp.failure(reply)
        return SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
    }

    private fun kids(keys: JsonObject): List<String?> =
        (keys["keys"] as? JsonArray).orEmpty().map { (it as? JsonObject)?.string("kid") }

    override fun toString(): String = "ChatGptPlanFlow(registration=$registration, consent=$consent)"

    /** The addresses of OpenAI's discovery document that Kestrel trusts. */
    internal class Discovery(val issuer: String, val jwks: HttpUrl, val revocation: HttpUrl)

    companion object {
        private const val CALLBACK_WAIT_MILLIS = 5 * 60_000L
        private val REVOKE_PAUSES = listOf(1_000L, 3_000L)

        /**
         * Reads `https://auth.openai.com/.well-known/openid-configuration` and trusts it only when its `issuer` is
         * `https://auth.openai.com` and its `jwks_uri` and `revocation_endpoint` are HTTPS on `auth.openai.com`;
         * otherwise "OpenAI's sign-in returned an address that Kestrel does not trust." (design D9).
         */
        internal suspend fun discover(http: ProviderHttp): Discovery {
            val reply = http.exchange(SignInHttp.get(ChatGptPlanAuth.DISCOVERY_URL.toHttpUrl()))
            if (reply.status !in 200..299) throw SignInHttp.failure(reply)
            val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
            fun trusted(key: String) = body.string(key)?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == ChatGptPlanAuth.HOST }
            val jwks = trusted("jwks_uri")
            val revocation = trusted("revocation_endpoint")
            if (body.string("issuer") != ChatGptPlanAuth.ISSUER || jwks == null || revocation == null) {
                throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
            }
            return Discovery(ChatGptPlanAuth.ISSUER, jwks, revocation)
        }

        /**
         * Signs out (design D9; OpenAI's "Accounts and sessions"): revokes [token]'s refresh token at the checked
         * revocation endpoint, with the issued client ID, while `auth.openai.com` is still on the allowlist. An empty 200
         * is success; a network failure or a 5xx is tried again twice, after 1 s and 3 s. Returns whether OpenAI confirmed
         * it; the caller deletes the profile either way, and the registration and the host ID stay.
         */
        suspend fun revoke(http: ProviderHttp, token: Credential.OAuthToken, pause: suspend (Long) -> Unit = { delay(it) }): Boolean {
            val clientId = token.clientId ?: return false
            val refresh = token.refresh ?: return false
            for (attempt in 0..REVOKE_PAUSES.size) {
                if (attempt > 0) pause(REVOKE_PAUSES[attempt - 1])
                try {
                    val endpoint = discover(http).revocation
                    val reply = http.exchange(
                        SignInHttp.form(endpoint, "token" to refresh, "token_type_hint" to "refresh_token", "client_id" to clientId),
                    )
                    if (reply.status in 200..299) return true
                    if (reply.status !in 500..599) return false
                } catch (e: ProviderException) {
                    // No network or a server error: try again.
                } catch (e: SignInFailed) {
                    return false
                }
            }
            return false
        }
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/signin/OpenRouterBrowserFlow.kt`:

```kotlin
package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.ApiKeys
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * OpenRouter's browser sign-in (design D9 and D10, research r32): PKCE that OpenRouter documents for any app, without a
 * client registration, so it borrows nothing. The redirect is `http://localhost:3000/openrouter-oauth/callback`, or the
 * same path on a free port, because OpenRouter accepts any loopback port; the server listens on both loopback addresses,
 * because Chrome tries `::1` first for `localhost`. The code is exchanged for an API key, which has no refresh.
 */
class OpenRouterBrowserFlow(private val http: ProviderHttp) : BrowserFlow {
    override val signInHosts: Set<String> = setOf("openrouter.ai")

    override suspend fun run(loopback: LoopbackListener, openBrowser: (String) -> Unit): SignInResult {
        val session = loopback.open(LoopbackAddresses.BOTH, PREFERRED_PORT, PATH)
            ?: loopback.open(LoopbackAddresses.BOTH, null, PATH)
            // No loopback port opened, so the browser was never asked: the sign-in did not finish.
            ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val callback = session.use {
            val redirect = "http://localhost:${session.port}$PATH".toHttpUrl().newBuilder().addQueryParameter("state", state).build()
            val page = "https://openrouter.ai/auth".toHttpUrl().newBuilder()
                .addQueryParameter("callback_url", redirect.toString())
                .addQueryParameter("code_challenge", Pkce.challenge(verifier))
                .addQueryParameter("code_challenge_method", "S256")
                .build()
            openBrowser(page.toString())
            session.await(state, CALLBACK_WAIT_MILLIS) ?: throw SignInFailed(SignInFailure.NO_CALLBACK)
        }.query
        val code = callback["code"] ?: throw SignInFailed(SignInFailure.REFUSED)
        val body = buildJsonObject {
            put("code", JsonPrimitive(code))
            put("code_verifier", JsonPrimitive(verifier))
            put("code_challenge_method", JsonPrimitive("S256"))
        }
        val reply = http.exchange(SignInHttp.json("https://openrouter.ai/api/v1/auth/keys".toHttpUrl(), body))
        if (SignInHttp.temporary(reply.status)) throw SignInHttp.failure(reply)
        if (reply.status !in 200..299) throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
        val key = SignInHttp.parse(reply)?.string("key")?.takeIf(ApiKeys::acceptable) ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
        return SignInResult(Credential.ApiKey(key))
    }

    override fun toString(): String = "OpenRouterBrowserFlow"

    private companion object {
        const val PREFERRED_PORT = 3000
        const val PATH = "/openrouter-oauth/callback"
        const val CALLBACK_WAIT_MILLIS = 5 * 60_000L
    }
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 80 tests (R₀ + 63 + 17): `IdTokensTest` 3, `ChatGptPlanFlowTest` 11 and `OpenRouterBrowserFlowTest` 3.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`, with `:core:providers` R₀ + 80 and the other modules as before, lint without issues, and the audit: `:core:privacy` is an Android module, so its reports are `core\privacy\build\reports\kestrel-audit\network-apis-debug.txt` and `network-apis-release.txt`; each starts with "No network API use outside the exceptions." and lists `LoopbackServer`'s uses of `java/net/ServerSocket` and `java/net/Socket` under "Allowed by audit/rules.txt:", all in `dev/kestrel/privacy/network/`. `core\providers\build\reports\kestrel-audit\network-apis.txt` (a plain Kotlin module) lists no finding.

- [ ] **Step 8: Run `LoopbackServerTest` on the emulator**

Wait for the emulator lock and take it, as "Before you start" says, with the name `plan 4C task 4`. Delete it at the end of this step, also when a command fails: `Remove-Item .superpowers\sdd\emulator.lock`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.LoopbackServerTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`, and the result file in `core\privacy\build\outputs\androidTest-results\connected\debug\` shows the 6 tests of `LoopbackServerTest` and 0 failures (the emulator has an IPv6 loopback, so none is skipped). The device tests of `:core:privacy` are now Q₀ + 6.

- [ ] **Step 9: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 10: Commit**

```powershell
$main = 'core/providers/src/main/kotlin/dev/kestrel/providers/signin'
$test = 'core/providers/src/test/kotlin/dev/kestrel/providers/signin'
$fix = 'core/providers/src/test/resources/fixtures'
git add core/privacy/src/main/kotlin/dev/kestrel/privacy/network/LoopbackServer.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/LoopbackServerTest.kt
git add "$main/Loopback.kt" "$main/IdTokens.kt" "$main/ChatGptPlanFlow.kt" "$main/OpenRouterBrowserFlow.kt"
git add "$test/TestIdTokens.kt" "$test/FakeLoopback.kt" "$test/IdTokensTest.kt" "$test/ChatGptPlanFlowTest.kt" "$test/OpenRouterBrowserFlowTest.kt" "$fix/chatgpt-plan/openid-configuration.json" "$fix/chatgpt-plan/token.json"
git commit -m "feat(providers): add browser sign-in with the loopback server, the ChatGPT plan's flow and OpenRouter's" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Copilot and the account lists

**Files:**
- Create: `core/providers/src/main/kotlin/dev/kestrel/providers/copilot/CopilotDomain.kt`, `CopilotDiscovery.kt`, `CopilotModels.kt`, `CopilotHeaders.kt`, `CopilotAllowance.kt`, `CopilotTargets.kt`, `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/ChatGptPlanModels.kt`, `XaiModels.kt`
- Modify: `core/providers/src/main/kotlin/dev/kestrel/providers/client/TargetResolver.kt` (`OfferedModels` and `ModelDefaults`), `docs/superpowers/specs/2026-11-12-kestrel-design.md` (10.5 and B27)
- Test: `core/providers/src/test/kotlin/dev/kestrel/providers/copilot/CopilotDomainTest.kt`, `CopilotDiscoveryTest.kt`, `CopilotAllowanceTest.kt`, `CopilotModelsTest.kt`, `CopilotHeadersTest.kt`, `CopilotTargetsTest.kt`, `discovery/ChatGptPlanModelsTest.kt`, `XaiModelsTest.kt`, `client/OfferedModelsTest.kt`; fixtures `core/providers/src/test/resources/fixtures/copilot/user.json`, `copilot/models.json`, `chatgpt-plan/models.json`, `xai/models.json`

**Interfaces:**
- Consumes: Task 1's `CredentialManager`, `SignInFailed`, `SignInRegistry`, `SignInKind`, `SignInFixtures`; Task 2's `ProviderTargets`, `ModelEntries`; plan 4A's `CatalogLoader.COPILOT` and `normalise`, `CopilotOverrides` (`runtimeHeaders`, `ideHeaders`, `lightweight`, `versatile`), `DiscoveredModel`, `ModelDiscovery`, `RequestHeaders`, `StyleOptions`, `CompatFlags`.
- Produces (package `dev.kestrel.providers.copilot`): `object CopilotDomain { const val PUBLIC; fun userUrl(domain: String): HttpUrl; fun fallbackBase(domain: String): HttpUrl; fun trusted(host: String, domain: String): Boolean; fun accept(address: String, domain: String): HttpUrl? }`; `class CopilotDiscovery(http: ProviderHttp)` with `suspend fun apiBase(token: Credential.GitHubToken): HttpUrl`; `class CopilotModels(http, base: HttpUrl, auth: RequestAuth, runtimeHeaders: Map<String, String>) : ModelDiscovery` with `companion object { fun model(entry: JsonObject): DiscoveredModel?; fun styleFor(id: String, vendor: String?, endpoints: List<String>): RequestStyle }`; `object CopilotHeaders { fun forRequests(runtime: Map<String, String>, extra: Map<String, String> = emptyMap()): RequestHeaders; fun initiator(request: ChatRequest): String; fun hasImage(request: ChatRequest): Boolean }`; `data class CopilotQuota(val label: String, val percentRemaining: Double)`; `class CopilotAllowance(http, ideHeaders: Map<String, String>)` with `suspend fun check(token: Credential.GitHubToken): List<CopilotQuota>`; `class CopilotTargets(catalog, credentials: CredentialManager, discovery: CopilotDiscovery, updateBase: suspend (Profile, HttpUrl) -> Unit) : ProviderTargets` with `fun forget(profileId: String)` and `companion object { val COMPAT: CompatFlags; fun options(style: RequestStyle, compat: CompatFlags = CompatFlags()): StyleOptions }`. Package `dev.kestrel.providers.discovery`: `class ChatGptPlanModels(http: ProviderHttp, auth: RequestAuth) : ModelDiscovery`; `class XaiModels(http: ProviderHttp, auth: RequestAuth, headers: Map<String, String> = emptyMap()) : ModelDiscovery`. Package `dev.kestrel.providers.client`: `data class ModelDefaults(val lightweight: String?, val versatile: String?)`; `object OfferedModels { fun rows(catalog: Catalog, providerId: String, choiceId: String, discovered: List<DiscoveredModel>): List<ModelEntry>; fun defaults(catalog: Catalog, providerId: String, rows: List<ModelEntry>): ModelDefaults }`.

Design D12 and D13; research r27, r28, r31 and r46. Copilot has no token exchange: the GitHub token is the bearer of every call. `CopilotDiscovery` reads `endpoints.api` with only `Accept` and the bearer and trusts only GitHub's proxy, `*.githubcopilot.com` or a tenant's own domain; `CopilotTargets` discovers before the first Copilot request in each process, stores a changed base through `updateBase` (which waits for the allowlist in Task 6), and uses the stored base when the network fails. Each model's style follows its `supported_endpoints`; Anthropic Messages goes to `<base>/messages` without thinking and with sanitised tool-call IDs, and Chat Completions gets Copilot's flags through `StyleOptions.compat`, which plan 4B's adapter puts before its own defaults; with `supportsUsageInStreaming: false`, its usage is estimated. Every request carries the runtime headers, `x-initiator` and, with an image, `Copilot-Vision-Request`, never `Openai-Intent`. "Check allowance" sends the IDE headers with `Authorization: token` and keeps only numbers. The ChatGPT plan's list keeps only `visibility: list` rows, xAI's list maps `api_backend` to the style. `OfferedModels` offers only the listed rows for the sign-ins whose list is the account's own and the catalog's rows beside the list for the others, and picks Copilot's defaults by name. `CopilotTargets` keeps the accepted base per profile for the process, turns an address that it does not trust into `ProviderException(Malformed)`, and on Chat Completions puts Copilot's four flags over the model's own compat, which keeps the rest, such as its reasoning efforts. Plan 4B's Anthropic adapter sends Copilot's Claude models without thinking blocks, also the Claude 5 models that think by default (as OpenClaw's Copilot replay policy does), and it sends REQUIRED to those models as `auto` (except Claude Haiku 5.5, which keeps `any`) and without a temperature.

- [ ] **Step 1: Write the fixtures and the failing tests**

Create `core/providers/src/test/resources/fixtures/copilot/user.json`:

```json
{
  "login": "test-login",
  "copilot_plan": "individual",
  "endpoints": {
    "api": "https://api.individual.githubcopilot.com",
    "proxy": "https://proxy.individual.githubcopilot.com"
  },
  "quota_snapshots": {
    "premium_interactions": { "entitlement": 300, "remaining": 210, "percent_remaining": 70.0, "unlimited": false },
    "chat": { "entitlement": 0, "percent_remaining": 100, "unlimited": true },
    "completions": { "percent_remaining": null }
  }
}
```

Create `core/providers/src/test/resources/fixtures/copilot/models.json`:

```json
{
  "object": "list",
  "data": [
    {
      "id": "gpt-6-luna", "name": "GPT-6 Luna", "object": "model", "vendor": "OpenAI", "model_picker_enabled": true,
      "policy": { "state": "enabled" }, "supported_endpoints": ["/responses", "/chat/completions"],
      "capabilities": { "type": "chat", "limits": { "max_context_window_tokens": 400000, "max_output_tokens": 128000 },
        "supports": { "streaming": true, "tool_calls": true, "vision": true, "reasoning_effort": ["low", "medium", "high"] } }
    },
    {
      "id": "gpt-6.1-terra", "name": "GPT-6.1 Terra", "object": "model", "vendor": "OpenAI", "model_picker_enabled": true,
      "supported_endpoints": ["/v1/responses/"],
      "capabilities": { "type": "chat", "supports": { "streaming": true, "tool_calls": true, "vision": true } }
    },
    {
      "id": "claude-haiku-5.5", "name": "Claude Haiku 5.5", "object": "model", "vendor": "Anthropic", "model_picker_enabled": true,
      "policy": { "state": "enabled" }, "supported_endpoints": ["/v1/messages", "/chat/completions"],
      "capabilities": { "type": "chat", "limits": { "max_context_window_tokens": 200000, "max_output_tokens": 64000 },
        "supports": { "streaming": true, "tool_calls": true, "vision": true } }
    },
    {
      "id": "gemini-3.8-flash", "name": "Gemini 3.8 Flash", "object": "model", "vendor": "Google", "model_picker_enabled": true,
      "supported_endpoints": ["/chat/completions"],
      "capabilities": { "type": "chat", "supports": { "streaming": true, "tool_calls": true, "vision": true } }
    },
    {
      "id": "gpt-5.4-nano", "name": "GPT-5.4 nano", "object": "model", "vendor": "OpenAI", "model_picker_enabled": true,
      "capabilities": { "type": "chat", "supports": { "streaming": true, "tool_calls": false } }
    },
    { "id": "gpt-hidden", "name": "Hidden", "object": "model", "model_picker_enabled": false, "capabilities": { "type": "chat", "supports": { "tool_calls": true } } },
    { "id": "gpt-disabled", "name": "Disabled", "object": "model", "model_picker_enabled": true, "policy": { "state": "disabled" }, "capabilities": { "type": "chat" } },
    { "id": "gpt-unconfigured", "name": "Unconfigured", "model_picker_enabled": true, "policy": { "state": "unconfigured" } },
    { "id": "text-embedding-4", "name": "Embedding", "object": "model", "model_picker_enabled": true, "capabilities": { "type": "embeddings" } },
    { "id": "gpt-no-stream", "name": "No stream", "object": "model", "model_picker_enabled": true, "capabilities": { "type": "chat", "supports": { "streaming": false, "tool_calls": true } } },
    { "id": "accounts/acme/models/custom", "name": "Custom", "object": "model", "model_picker_enabled": true },
    { "id": "router", "name": "Router", "object": "router", "model_picker_enabled": true }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/chatgpt-plan/models.json`:

```json
{
  "models": [
    { "slug": "gpt-6.1-sol", "display_name": "GPT-6.1 Sol", "visibility": "list" },
    { "slug": "gpt-6-luna", "display_name": "GPT-6 Luna", "visibility": "list" },
    { "slug": "gpt-internal-eval", "display_name": "Internal", "visibility": "hide" },
    { "slug": "codex-auto-review", "visibility": "none" }
  ]
}
```

Create `core/providers/src/test/resources/fixtures/xai/models.json`:

```json
{
  "data": [
    { "id": "grok-4.7", "api_backend": "responses", "context_window": 2000000, "max_completion_tokens": 64000, "supports_reasoning_effort": true, "reasoning_efforts": ["low", "high"] },
    { "id": "grok-code-2", "api_backend": "chat", "context_window": 256000 },
    { "id": "grok-imagine", "api_backend": "language" }
  ]
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/copilot/CopilotDomainTest.kt`:

```kotlin
package dev.kestrel.providers.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotDomainTest {
    @Test
    fun copilotsApiMayLiveOnlyOnGitHubsHostsOrTheTenantsOwn() {
        assertTrue(CopilotDomain.trusted("copilot-proxy.githubusercontent.com", "github.com"))
        assertTrue(CopilotDomain.trusted("api.business.githubcopilot.com", "github.com"))
        assertTrue(CopilotDomain.trusted("copilot-api.acme.ghe.com", "acme.ghe.com"))
        assertTrue(CopilotDomain.trusted("acme.ghe.com", "acme.ghe.com"))
        assertFalse(CopilotDomain.trusted("copilot-api.acme.ghe.com", "github.com"))
        assertFalse(CopilotDomain.trusted("githubcopilot.com.example.com", "github.com"))
        assertFalse(CopilotDomain.trusted("evil-acme.ghe.com", "acme.ghe.com"))
    }

    @Test
    fun anApiAddressMustBeHttpsWithoutUserQueryOrFragment() {
        assertEquals("https://api.individual.githubcopilot.com/", CopilotDomain.accept("https://api.individual.githubcopilot.com/", "github.com").toString())
        assertNull(CopilotDomain.accept("http://api.individual.githubcopilot.com", "github.com"))
        assertNull(CopilotDomain.accept("https://user@api.individual.githubcopilot.com", "github.com"))
        assertNull(CopilotDomain.accept("https://api.individual.githubcopilot.com/?a=1", "github.com"))
        assertNull(CopilotDomain.accept("https://api.individual.githubcopilot.com/#a", "github.com"))
        assertNull(CopilotDomain.accept("https://api.example.com", "github.com"))
        assertEquals("https://api.individual.githubcopilot.com/", CopilotDomain.fallbackBase("github.com").toString())
        assertEquals("https://copilot-api.acme.ghe.com/", CopilotDomain.fallbackBase("acme.ghe.com").toString())
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/copilot/CopilotDiscoveryTest.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFailed
import dev.kestrel.providers.signin.SignInFailure
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Copilot's discovery of its API address (design D12; research r27 §4). */
class CopilotDiscoveryTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val token = Credential.GitHubToken("test-github-token", "github.com")

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun theApiAddressComesFromTheUserCallWithOnlyAcceptAndTheBearer() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))

        val base = CopilotDiscovery(http).apiBase(token)

        assertEquals("https://api.individual.githubcopilot.com/", base.toString())
        assertEquals("https://api.github.com/copilot_internal/user", calls.requests.single().toString())
        val sent = server.takeRequest()
        assertEquals("Bearer test-github-token", sent.headers["Authorization"])
        assertEquals("application/json", sent.headers["Accept"])
        assertNull(sent.headers["Editor-Version"])
        assertNull(sent.headers["Copilot-Integration-Id"])
    }

    @Test
    fun aMissingAddressGivesTheFallbackAndAnUntrustedOneIsRefused() = runBlocking {
        server.enqueue(json(200, """{"endpoints":{"api":""}}"""))
        server.enqueue(json(200, """{"login":"x"}"""))
        server.enqueue(json(200, read("copilot/user.json").replace("https://api.individual.githubcopilot.com", "https://copilot.example.com")))

        assertEquals("https://copilot-api.acme.ghe.com/", CopilotDiscovery(http).apiBase(token.copy(domain = "acme.ghe.com")).toString())
        assertEquals("https://api.individual.githubcopilot.com/", CopilotDiscovery(http).apiBase(token).toString())
        try {
            CopilotDiscovery(http).apiBase(token)
            fail("expected SignInFailed")
        } catch (e: SignInFailed) {
            assertEquals(SignInFailure.UNTRUSTED_ADDRESS, e.kind)
        }
        assertEquals("https://api.acme.ghe.com/copilot_internal/user", calls.requests.first().toString())
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/copilot/CopilotAllowanceTest.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** "Check allowance" (design D12; research r27 §6 and §8). */
class CopilotAllowanceTest {
    private val server = MockWebServer()
    private lateinit var http: ProviderHttp
    private val token = Credential.GitHubToken("test-github-token", "github.com")

    @Before
    fun start() {
        server.start()
        http = ProviderHttp(RedirectingCalls(server))
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun theAllowanceSendsTheIdeHeadersWithTheTokenSchemeAndKeepsOnlyNumbers() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))
        val ide = mapOf("Accept-Encoding" to "identity", "Editor-Version" to "vscode/1.107.0", "Editor-Plugin-Version" to "copilot-chat/0.35.0", "User-Agent" to "GitHubCopilotChat/0.35.0")

        val quotas = CopilotAllowance(http, ide).check(token)

        assertEquals(listOf(CopilotQuota("premium", 70.0), CopilotQuota("chat", 100.0)), quotas)
        val sent = server.takeRequest()
        assertEquals("token test-github-token", sent.headers["Authorization"])
        assertEquals("2025-04-01", sent.headers["X-Github-Api-Version"])
        assertEquals("vscode/1.107.0", sent.headers["Editor-Version"])
        assertEquals("GitHubCopilotChat/0.35.0", sent.headers["User-Agent"])
        assertNull(sent.headers["Copilot-Integration-Id"])
        server.enqueue(json(200, """{"quota_snapshots":{}}"""))
        assertEquals(emptyList<CopilotQuota>(), CopilotAllowance(http, ide).check(token))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/copilot/CopilotModelsTest.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.catalog
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Copilot's model list, its filters and the style per model (design D12; research r27 §5 and §7). */
class CopilotModelsTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun theListKeepsTheVisibleChatModelsThatStreamWithTheirStylesAndCapabilities() = runBlocking {
        server.enqueue(fixture(200, "copilot/models.json"))
        val runtime = catalog.overrides.copilot.runtimeHeaders

        val models = CopilotModels(http, "https://api.individual.githubcopilot.com/".toHttpUrl(), ApiKeyAuth("test-github-token", KeyHeader.BEARER), runtime).list()

        assertEquals(listOf("gpt-6-luna", "gpt-6.1-terra", "claude-haiku-5.5", "gemini-3.8-flash", "gpt-5.4-nano"), models.map { it.id })
        assertEquals(
            DiscoveredModel(
                "gpt-6-luna", "GPT-6 Luna", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, reasoningEfforts = listOf("low", "medium", "high"),
                contextWindow = 400_000, maxTokens = 128_000, tools = true, streaming = true, style = RequestStyle.RESPONSES,
            ),
            models[0],
        )
        assertEquals(RequestStyle.RESPONSES, models[1].style)
        assertEquals(RequestStyle.ANTHROPIC, models[2].style)
        assertEquals(RequestStyle.CHAT_COMPLETIONS, models[3].style)
        assertEquals(false, models[4].tools)
        assertEquals("https://api.individual.githubcopilot.com/models", calls.requests.single().toString())
        val sent = server.takeRequest()
        for ((name, value) in runtime) assertEquals(name, value, sent.headers[name])
        assertEquals("Bearer test-github-token", sent.headers["Authorization"])
    }

    @Test
    fun theStyleIsThePreferredOneOrTheFirstThatTheModelLists() {
        assertEquals(RequestStyle.ANTHROPIC, CopilotModels.styleFor("claude-sonnet-5.5", null, emptyList()))
        assertEquals(RequestStyle.ANTHROPIC, CopilotModels.styleFor("sonnet", "anthropic", listOf("/v1/messages/")))
        assertEquals(RequestStyle.RESPONSES, CopilotModels.styleFor("claude-x", null, listOf("/responses")))
        assertEquals(RequestStyle.CHAT_COMPLETIONS, CopilotModels.styleFor("gemini-4-argon", null, listOf("/chat/completions", "/responses")))
        assertEquals(RequestStyle.CHAT_COMPLETIONS, CopilotModels.styleFor("gpt-x", null, listOf("/chat/completions")))
        assertEquals(RequestStyle.RESPONSES, CopilotModels.styleFor("gpt-x", null, listOf("/embeddings")))
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/copilot/CopilotHeadersTest.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.http.RequestMeta
import dev.kestrel.providers.signin.SignInFixtures.catalog
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `x-initiator`, the vision header and the runtime headers of Copilot's requests (design D12; research r27 §6). */
class CopilotHeadersTest {
    private val runtime = catalog.overrides.copilot.runtimeHeaders
    private val ask = ChatMessage.User(listOf(Part.Text("Open the settings.")))

    private fun request(vararg messages: ChatMessage, agentStep: Boolean = false) =
        ChatRequest("You are a test.", messages.toList(), maxOutputTokens = 100, meta = RequestMeta(agentStep = agentStep))

    @Test
    fun aTasksFirstRequestIsTheUsersAndEveryLaterStepOrToolResultIsTheAgents() {
        val headers = CopilotHeaders.forRequests(runtime)
        val call = ChatMessage.Assistant(listOf(AssistantPart.ToolCall("call-1", "tap", JsonObject(emptyMap()))))
        val result = ChatMessage.ToolResult("call-1", "tap", "done")

        assertEquals("user", headers.headers(request(ask))["x-initiator"])
        assertEquals("agent", headers.headers(request(ask, agentStep = true))["x-initiator"])
        assertEquals("agent", headers.headers(request(ask, call, result))["x-initiator"])
    }

    @Test
    fun anImageAddsTheVisionHeaderAndTheRuntimeHeadersAreAlwaysSent() {
        val screenshot = ChatMessage.User(listOf(Part.Text("What is on the screen?"), Part.Image(ImageType.PNG, byteArrayOf(1, 2))))

        val sent = CopilotHeaders.forRequests(runtime, mapOf("X-Test" to "1")).headers(request(screenshot))

        assertEquals("true", sent["Copilot-Vision-Request"])
        assertEquals("GitHubCopilotChat/0.35.0", sent["User-Agent"])
        assertEquals("copilot-developer-cli", sent["Copilot-Integration-Id"])
        assertEquals("1", sent["X-Test"])
        assertNull(sent["Openai-Intent"])
        assertNull(CopilotHeaders.forRequests(runtime).headers(request(ask))["Copilot-Vision-Request"])
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/copilot/CopilotTargetsTest.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.signin.SignInFixtures.catalog
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Copilot's targets: the route per model, the discovered base and its update (design D12). */
class CopilotTargetsTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private lateinit var credentials: CredentialManager
    private val updates = mutableListOf<HttpUrl>()
    private val request = ChatRequest("", listOf(ChatMessage.User(listOf(Part.Text("Hi")))), maxOutputTokens = 16)

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
        val vault = object : CredentialVault {
            override suspend fun read(id: String): Credential? = Credential.GitHubToken("test-github-token", "github.com")

            override suspend fun write(id: String, credential: Credential) = Unit

            override suspend fun delete(id: String) = Unit
        }
        credentials = CredentialManager(vault, http, catalog, CoroutineScope(Dispatchers.Default))
    }

    @After
    fun stop() {
        server.close()
    }

    private fun profile(base: String?) = Profile(
        "test-profile", "Test", "github-copilot", "github-copilot", AuthMethod.DEVICE_CODE, "test-credential", baseUrl = base,
        lightweight = ProfileModel("claude-haiku-5.5"), versatile = ProfileModel("gemini-3.8-flash"),
    )

    private fun targets() = CopilotTargets(catalog, credentials, CopilotDiscovery(http)) { _, base -> updates += base }

    @Test
    fun eachRouteGetsItsPathOptionsHeadersAndTheBearer() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))
        val targets = targets()
        val profile = profile("https://api.individual.githubcopilot.com/")

        val claude = targets.target(profile, ModelRole.LIGHTWEIGHT, ModelEntry("claude-haiku-5.5", "Claude Haiku 5.5", style = RequestStyle.ANTHROPIC))
        val gemini = targets.target(profile, ModelRole.VERSATILE, ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", style = RequestStyle.CHAT_COMPLETIONS))

        assertEquals(RequestStyle.ANTHROPIC, claude.style)
        assertEquals("/messages", claude.options.messagesPath)
        assertTrue(claude.options.stripThinking && claude.options.sanitizeToolCallIds && claude.options.stream == true)
        assertEquals(RequestStyle.CHAT_COMPLETIONS, gemini.style)
        assertEquals(CopilotTargets.COMPAT, gemini.options.compat)
        assertEquals("https://api.individual.githubcopilot.com/", gemini.baseUrl.toString())
        assertEquals("user", claude.headers.headers(request)["x-initiator"])
        assertEquals("copilot-developer-cli", claude.headers.headers(request)["Copilot-Integration-Id"])
        assertEquals(mapOf("Authorization" to "Bearer test-github-token"), claude.auth.headers(HttpRequest("POST", "https://example.com/".toHttpUrl())))
        assertEquals(emptyList<HttpUrl>(), updates)
        assertEquals(1, calls.requests.size)

        // An address that Kestrel does not trust fails the request as an answer that Kestrel cannot read.
        server.enqueue(json(200, read("copilot/user.json").replace("https://api.individual.githubcopilot.com", "https://copilot.example.com")))
        try {
            targets().target(profile, ModelRole.LIGHTWEIGHT, ModelEntry("gpt-6-luna", "GPT-6 Luna"))
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.Malformed, e.error)
        }
    }

    @Test
    fun aChangedBaseIsStoredOnceAndLaterRequestsInTheProcessUseIt() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))
        val targets = targets()
        val profile = profile("https://api.business.githubcopilot.com/")

        val first = targets.target(profile, ModelRole.LIGHTWEIGHT, ModelEntry("gpt-6-luna", "GPT-6 Luna", style = RequestStyle.RESPONSES))
        // A client that sub-project 5 kept still holds the profile with the old base.
        val later = targets.target(profile, ModelRole.LIGHTWEIGHT, ModelEntry("gpt-6-luna", "GPT-6 Luna"))

        assertEquals("https://api.individual.githubcopilot.com/", first.baseUrl.toString())
        assertEquals(listOf("https://api.individual.githubcopilot.com/".toHttpUrl()), updates)
        assertEquals(first.baseUrl, later.baseUrl)
        assertEquals(RequestStyle.RESPONSES, later.style)
        assertEquals(1, calls.requests.size)
    }

    @Test
    fun withoutNetworkTheStoredBaseIsUsedForThisProcess() = runBlocking {
        server.close()

        val target = targets().target(profile("https://api.business.githubcopilot.com/"), ModelRole.LIGHTWEIGHT, ModelEntry("gpt-6-luna", "GPT-6 Luna"))

        assertEquals("https://api.business.githubcopilot.com/", target.baseUrl.toString())
        assertEquals(emptyList<HttpUrl>(), updates)
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/ChatGptPlanModelsTest.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The ChatGPT plan's model list (design D13 and D17; research r46). */
class ChatGptPlanModelsTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val auth = ApiKeyAuth("test-access-1", KeyHeader.BEARER)

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun onlyTheListedRowsAreShownEachWithToolSupportOnResponses() = runBlocking {
        server.enqueue(fixture(200, "chatgpt-plan/models.json"))

        val models = ChatGptPlanModels(http, auth).list()

        assertEquals(listOf("gpt-6.1-sol" to "GPT-6.1 Sol", "gpt-6-luna" to "GPT-6 Luna"), models.map { it.id to it.name })
        assertEquals(listOf(true, true), models.map { it.tools })
        assertEquals(setOf(RequestStyle.RESPONSES), models.map { it.style }.toSet())
        assertEquals("https://api.openai.com/v1/models", calls.requests.single().toString())
        assertEquals("Bearer test-access-1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun anAccountWithoutAPlanGetsItsOwnCode() {
        server.enqueue(json(403, """{"error":{"code":"subscription_sharing_user_not_eligible","message":"x"}}"""))

        try {
            runBlocking { ChatGptPlanModels(http, auth).list() }
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.QuotaOrPolicy(403, "subscription_sharing_user_not_eligible"), e.error)
        }
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/discovery/XaiModelsTest.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The model list of xAI's sign-in on the Grok CLI proxy (design D13; research r31). */
class XaiModelsTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun eachRowsBackendGivesItsStyleAndLanguageRowsAreDropped() = runBlocking {
        server.enqueue(fixture(200, "xai/models.json"))
        val identity = mapOf("X-XAI-Token-Auth" to "xai-grok-cli", "x-grok-client-version" to "1.0.13")

        val models = XaiModels(ProviderHttp(calls), ApiKeyAuth("test-xai-access-1", KeyHeader.BEARER), identity).list()

        assertEquals(listOf("grok-4.7" to RequestStyle.RESPONSES, "grok-code-2" to RequestStyle.CHAT_COMPLETIONS), models.map { it.id to it.style })
        assertEquals(listOf("low", "high"), models[0].reasoningEfforts)
        assertEquals(2_000_000, models[0].contextWindow)
        assertEquals(64_000, models[0].maxTokens)
        assertEquals(false, models[1].reasoning)
        assertEquals("https://cli-chat-proxy.grok.com/v1/models", calls.requests.single().toString())
        assertEquals("xai-grok-cli", server.takeRequest().headers["X-XAI-Token-Auth"])
    }
}
```

Create `core/providers/src/test/kotlin/dev/kestrel/providers/client/OfferedModelsTest.kt`:

```kotlin
package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.signin.SignInFixtures.catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows that a profile offers per choice, and the defaults among them (design D12 and D13). */
class OfferedModelsTest {
    private fun listed(vararg ids: Pair<String, String>, tools: Boolean? = true) = ids.map { (id, name) -> DiscoveredModel(id, name, tools = tools) }

    @Test
    fun anApiKeyProfileOffersTheCatalogsRowsBesideItsList() {
        val rows = OfferedModels.rows(catalog, "amazon-bedrock-mantle", "mantle-api-key", listed("mistral.test-model" to "Mistral test"))

        val claudeRows = catalog.provider("amazon-bedrock-mantle")!!.models.map { it.id }
        assertTrue(claudeRows.isNotEmpty())
        assertEquals(claudeRows + "mistral.test-model", rows.map { it.id })
    }

    @Test
    fun copilotAndTheChatGptPlanOfferOnlyTheirListsWithTheCatalogsPrices() {
        val copilot = OfferedModels.rows(catalog, "github-copilot", "github-copilot", listed("claude-sonnet-5.5" to "Claude Sonnet 5.5", "copilot-new" to "New"))
        val plan = OfferedModels.rows(catalog, "openai", "chatgpt-plan", listed("gpt-6.1-sol" to "GPT-6.1 Sol", "gpt-plan-only" to "Plan only"))
        val noTools = OfferedModels.rows(catalog, "github-copilot", "github-copilot", listed("gpt-5.4-nano" to "GPT-5.4 nano", tools = false))

        assertEquals(listOf("claude-sonnet-5.5", "copilot-new"), copilot.map { it.id })
        assertTrue(copilot[0].prices.isNotEmpty())
        assertEquals(listOf("gpt-6.1-sol", "gpt-plan-only"), plan.map { it.id })
        assertEquals(ToolMode.NATIVE, plan[1].toolMode)
        assertEquals(setOf(InputKind.TEXT), plan[1].input)
        assertEquals(ToolMode.JSON, noTools.single().toolMode)
    }

    @Test
    fun copilotsDefaultsComeByNameAndTheOthersFromTheCatalogOrTheFirstRow() {
        fun copilot(vararg names: Pair<String, String>) =
            OfferedModels.defaults(catalog, "github-copilot", OfferedModels.rows(catalog, "github-copilot", "github-copilot", listed(*names)))

        assertEquals(
            ModelDefaults("gpt-6-luna", "gpt-6.1-terra"),
            copilot("claude-haiku-5.5" to "Claude Haiku 5.5", "gpt-6-luna" to "GPT-6 Luna", "gpt-6.1-terra" to "GPT-6.1 Terra"),
        )
        assertEquals(ModelDefaults("claude-haiku-5.5", "claude-haiku-5.5"), copilot("claude-haiku-5.5" to "Claude Haiku 5.5"))
        val openai = OfferedModels.rows(catalog, "openai", "openai-api-key", emptyList())
        assertEquals(ModelDefaults("gpt-6-luna", openai.first().id), OfferedModels.defaults(catalog, "openai", openai))
        assertEquals(ModelDefaults(null, null), OfferedModels.defaults(catalog, "ollama", emptyList()))
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'CopilotDomain'", "Unresolved reference 'CopilotTargets'", "Unresolved reference 'ChatGptPlanModels'", "Unresolved reference 'XaiModels'" and "Unresolved reference 'OfferedModels'".

- [ ] **Step 3: Write Copilot's package**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/copilot/CopilotDomain.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.RequestAuth
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Copilot's hosts on github.com and on a GHE.com tenant (design D12; research r27). */
object CopilotDomain {
    const val PUBLIC = "github.com"

    /** `https://api.<domain>/copilot_internal/user`, which names the account's API address and its allowance. */
    fun userUrl(domain: String): HttpUrl = "https://api.$domain/copilot_internal/user".toHttpUrl()

    /** The API base when the account names none: `https://api.individual.githubcopilot.com`, or `https://copilot-api.<tenant>`. */
    fun fallbackBase(domain: String): HttpUrl =
        (if (domain == PUBLIC) "https://api.individual.githubcopilot.com" else "https://copilot-api.$domain").toHttpUrl()

    /** Whether Copilot's API may live on [host]: GitHub's proxy, `*.githubcopilot.com`, or for a tenant its own domain. */
    fun trusted(host: String, domain: String): Boolean =
        host == "copilot-proxy.githubusercontent.com" || host.endsWith(".githubcopilot.com") ||
            (domain != PUBLIC && (host == domain || host.endsWith(".$domain")))

    /** [address] as an API base when it is HTTPS on a trusted host, without user, query or fragment; otherwise null. */
    fun accept(address: String, domain: String): HttpUrl? {
        val url = address.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        if (!trusted(url.host, domain)) return null
        return url.newBuilder().encodedPath("/" + url.encodedPath.trim('/')).build()
    }
}

/** The GitHub token in a header: `Bearer` for Copilot's API and its user call, `token` for the allowance (research r27). */
internal class GitHubAuth(private val token: String, private val scheme: String = "Bearer") : RequestAuth {
    override suspend fun headers(request: HttpRequest): Map<String, String> = mapOf("Authorization" to "$scheme $token")

    override val rejected: ProviderError = ProviderError.SignInExpired(CatalogLoader.COPILOT)

    override fun toString(): String = "GitHubAuth(scheme=$scheme)"
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/copilot/CopilotDiscovery.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFailed
import dev.kestrel.providers.signin.SignInFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl

/**
 * Finds a Copilot account's API address (design D12; research r27): `GET https://api.<domain>/copilot_internal/user` with
 * only `Accept` and the GitHub token as the bearer, and its `endpoints.api`. A missing or empty field gives the fallback
 * base; an address that is not HTTPS on a trusted host ends with "Copilot returned an address that Kestrel does not
 * trust." (UNTRUSTED_ADDRESS).
 */
class CopilotDiscovery(private val http: ProviderHttp) {
    private val json = Json { isLenient = true }

    suspend fun apiBase(token: Credential.GitHubToken): HttpUrl {
        val request = HttpRequest("GET", CopilotDomain.userUrl(token.domain), mapOf("Accept" to "application/json"))
        val reply = http.send(request, GitHubAuth(token.token))
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw ProviderException(ProviderError.Malformed)
        val api = (body["endpoints"] as? JsonObject)?.get("api")
        if (api == null || api is JsonNull || (api is JsonPrimitive && api.isString && api.content.isEmpty())) {
            return CopilotDomain.fallbackBase(token.domain)
        }
        val address = (api as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
        return CopilotDomain.accept(address, token.domain) ?: throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/copilot/CopilotModels.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.discovery.ModelDiscovery
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl

/**
 * Copilot's model list (design D12; research r27): `GET <base>/models` with the runtime headers and the bearer. A model is
 * shown when `model_picker_enabled` is true, its `policy.state` is neither `disabled` nor `unconfigured`, its `object` is
 * `model` and its `capabilities.type` `chat` (or absent), it streams (Kestrel streams every Copilot request) and its ID
 * does not start with `accounts/`. Its tool support, image input, reasoning efforts and limits come from its
 * capabilities, and its style from its `supported_endpoints`.
 */
class CopilotModels(
    private val http: ProviderHttp,
    private val base: HttpUrl,
    private val auth: RequestAuth,
    private val runtimeHeaders: Map<String, String>,
) : ModelDiscovery {
    private val json = Json { isLenient = true }

    override suspend fun list(): List<DiscoveredModel> {
        val url = base.newBuilder().addPathSegment("models").build()
        val reply = http.send(HttpRequest("GET", url, runtimeHeaders + ("Accept" to "application/json")), auth)
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        val data = body?.get("data") as? JsonArray ?: throw ProviderException(ProviderError.Malformed)
        return data.mapNotNull { (it as? JsonObject)?.let(::model) }
    }

    companion object {
        private val GEMINI = Regex("(?:^|[-_.])gemini(?:$|[-_.])")
        private val PATHS = mapOf(
            RequestStyle.ANTHROPIC to "/messages",
            RequestStyle.CHAT_COMPLETIONS to "/chat/completions",
            RequestStyle.RESPONSES to "/responses",
        )

        /** One row of the list, or null when D12's filters hide it. */
        fun model(entry: JsonObject): DiscoveredModel? {
            val id = entry.text("id")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("accounts/") } ?: return null
            if (entry.text("object")?.let { it != "model" } == true) return null
            val capabilities = entry["capabilities"] as? JsonObject
            if (capabilities?.text("type")?.let { it != "chat" } == true) return null
            if (entry.flag("model_picker_enabled") != true) return null
            if ((entry["policy"] as? JsonObject)?.text("state") in setOf("disabled", "unconfigured")) return null
            val supports = capabilities?.get("supports") as? JsonObject
            if (supports?.flag("streaming") == false) return null
            val limits = capabilities?.get("limits") as? JsonObject
            val efforts = (supports?.get("reasoning_effort") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            val endpoints = (entry["supported_endpoints"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            return DiscoveredModel(
                id = id,
                name = entry.text("name")?.takeIf { it.isNotBlank() } ?: id,
                input = if (supports?.flag("vision") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = !efforts.isNullOrEmpty(),
                reasoningEfforts = efforts?.takeIf { it.isNotEmpty() },
                contextWindow = limits?.number("max_context_window_tokens"),
                maxTokens = limits?.number("max_output_tokens"),
                tools = supports?.flag("tool_calls") == true,
                streaming = supports?.flag("streaming"),
                style = styleFor(id, entry.text("vendor"), endpoints),
            )
        }

        /**
         * The style of a model (research r27): the preferred style is Anthropic Messages for vendor `anthropic` or an ID
         * with `claude`, Chat Completions for a Gemini ID, otherwise Responses; the first of the preferred style, Responses,
         * Chat Completions and Anthropic Messages whose path the model lists wins, and without any the preferred style.
         */
        fun styleFor(id: String, vendor: String?, endpoints: List<String>): RequestStyle {
            val preferred = when {
                vendor.equals("anthropic", ignoreCase = true) || "claude" in id.lowercase() -> RequestStyle.ANTHROPIC
                GEMINI.containsMatchIn(id.lowercase()) -> RequestStyle.CHAT_COMPLETIONS
                else -> RequestStyle.RESPONSES
            }
            val listed = endpoints.map { it.trim().removePrefix("/v1").trimEnd('/') }.toSet()
            return listOf(preferred, RequestStyle.RESPONSES, RequestStyle.CHAT_COMPLETIONS, RequestStyle.ANTHROPIC)
                .firstOrNull { PATHS.getValue(it) in listed } ?: preferred
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/copilot/CopilotHeaders.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestHeaders

/**
 * The headers of Copilot's requests (design D12; research r27): the runtime headers of `overrides.json`, `x-initiator`,
 * and `Copilot-Vision-Request: true` when the request has an image. `x-initiator` is `agent` for an agent's step after a
 * task's first request ([dev.kestrel.providers.http.RequestMeta.agentStep]) or when the last message is a tool result,
 * and `user` otherwise. Kestrel sends no `Openai-Intent`. [extra] are the debug hook's headers for a retry in the owner's
 * session; release code passes none.
 */
object CopilotHeaders {
    fun forRequests(runtime: Map<String, String>, extra: Map<String, String> = emptyMap()): RequestHeaders = RequestHeaders { request ->
        val vision = if (hasImage(request)) mapOf("Copilot-Vision-Request" to "true") else emptyMap()
        runtime + ("x-initiator" to initiator(request)) + vision + extra
    }

    fun initiator(request: ChatRequest): String =
        if (request.meta.agentStep || request.messages.lastOrNull() is ChatMessage.ToolResult) "agent" else "user"

    fun hasImage(request: ChatRequest): Boolean =
        request.messages.any { message -> message is ChatMessage.User && message.parts.any { it is Part.Image } }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/copilot/CopilotAllowance.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One of Copilot's quotas: `premium` or `chat`, and the percentage that Copilot reports as left. */
data class CopilotQuota(val label: String, val percentRemaining: Double)

/**
 * "Check allowance" on the profile page (design D12; research r27 §6 and §8): `GET https://api.<domain>/copilot_internal/user`
 * with `Authorization: token <token>`, the IDE headers only and `X-Github-Api-Version: 2025-04-01`, never
 * `Copilot-Integration-Id`. It returns only the quotas whose `percent_remaining` is a number; under AI credits these may
 * be missing (research r28), and Kestrel never computes a remaining figure itself.
 */
class CopilotAllowance(private val http: ProviderHttp, private val ideHeaders: Map<String, String>) {
    private val json = Json { isLenient = true }

    suspend fun check(token: Credential.GitHubToken): List<CopilotQuota> {
        val headers = ideHeaders + mapOf("X-Github-Api-Version" to API_VERSION, "Accept" to "application/json")
        val reply = http.send(HttpRequest("GET", CopilotDomain.userUrl(token.domain), headers), GitHubAuth(token.token, "token"))
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        val snapshots = body?.get("quota_snapshots") as? JsonObject ?: return emptyList()
        return listOfNotNull(quota(snapshots, "premium_interactions", "premium"), quota(snapshots, "chat", "chat"))
    }

    private fun quota(snapshots: JsonObject, key: String, label: String): CopilotQuota? {
        val value = ((snapshots[key] as? JsonObject)?.get("percent_remaining") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return value?.let { CopilotQuota(label, it) }
    }

    private companion object {
        const val API_VERSION = "2025-04-01"
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/copilot/CopilotTargets.kt`:

```kotlin
package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.client.ProviderTargets
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.signin.SignInFailed
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Copilot's targets (design D12): each model's style from its `supported_endpoints`, the base that [discovery] finds
 * before the first Copilot request in each process, the runtime headers with `x-initiator` and the vision header, and the
 * GitHub token as the bearer. Anthropic Messages goes to `<base>/messages` with `stripThinking` (no thinking blocks for
 * any Claude model, as OpenClaw's Copilot replay policy does) and with sanitised
 * tool-call IDs; Chat Completions uses Copilot's compat flags; every Copilot request streams. When the base differs from
 * the profile's stored one, [updateBase] stores it and waits until the allowlist holds its host; when discovery fails
 * for lack of network, the stored base is used for this process. The base is kept per profile for the process, so that a
 * client that sub-project 5 keeps uses the new base too.
 */
class CopilotTargets(
    private val catalog: Catalog,
    private val credentials: CredentialManager,
    private val discovery: CopilotDiscovery,
    private val updateBase: suspend (Profile, HttpUrl) -> Unit,
) : ProviderTargets {
    private val bases = ConcurrentHashMap<String, HttpUrl>()

    /** Forgets [profileId]'s base, as after "Sign in again", which may be another GitHub account. */
    fun forget(profileId: String) {
        bases.remove(profileId)
    }

    override suspend fun target(profile: Profile, role: ModelRole, entry: ModelEntry): ModelTarget {
        val base = base(profile)
        val style = profile.model(role).style ?: entry.style ?: CopilotModels.styleFor(entry.id, null, emptyList())
        return ModelTarget(
            providerId = CatalogLoader.COPILOT,
            style = style,
            baseUrl = base,
            model = entry,
            auth = credentials.auth(profile, KeyHeader.BEARER),
            headers = CopilotHeaders.forRequests(catalog.overrides.copilot.runtimeHeaders),
            options = options(style, entry.compat),
        )
    }

    private suspend fun base(profile: Profile): HttpUrl {
        bases[profile.id]?.let { return it }
        val stored = profile.baseUrl?.toHttpUrlOrNull()
        val token = profile.credentialId?.let { credentials.read(it) } as? Credential.GitHubToken
            ?: throw ProviderException(ProviderError.SignInExpired(CatalogLoader.COPILOT))
        val found = try {
            discovery.apiBase(token)
        } catch (e: SignInFailed) {
            // Copilot named an address that Kestrel does not trust (design D12): the request fails, and no token goes there.
            throw ProviderException(ProviderError.Malformed)
        } catch (e: ProviderException) {
            if (stored != null && (e.error == ProviderError.Unreachable || e.error == ProviderError.Timeout)) {
                bases[profile.id] = stored
                return stored
            }
            throw e
        }
        if (found != stored) updateBase(profile, found)
        bases[profile.id] = found
        return found
    }

    companion object {
        /** Copilot's Chat Completions flags (research r27). */
        val COMPAT = CompatFlags(supportsStore = false, supportsDeveloperRole = false, supportsUsageInStreaming = false, maxTokensField = "max_tokens")

        /**
         * The options of a Copilot request in [style]. On Chat Completions, Copilot's flags replace those of the model's
         * [compat], which keeps the rest, such as its reasoning efforts.
         */
        fun options(style: RequestStyle, compat: CompatFlags = CompatFlags()): StyleOptions = when (style) {
            RequestStyle.ANTHROPIC -> StyleOptions(stripThinking = true, sanitizeToolCallIds = true, messagesPath = "/messages", stream = true)
            RequestStyle.CHAT_COMPLETIONS -> StyleOptions(
                compat = compat.copy(
                    supportsStore = COMPAT.supportsStore,
                    supportsDeveloperRole = COMPAT.supportsDeveloperRole,
                    supportsUsageInStreaming = COMPAT.supportsUsageInStreaming,
                    maxTokensField = COMPAT.maxTokensField,
                ),
                stream = true,
            )
            else -> StyleOptions(stream = true)
        }
    }
}
```

- [ ] **Step 4: Write the ChatGPT plan's and xAI's lists and the offered rows**

Create `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/ChatGptPlanModels.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The ChatGPT plan's model list (design D13; research r46; OpenAI's "Models and inference"): `GET
 * https://api.openai.com/v1/models` with the plan's bearer, whose rows have `slug`, `display_name` and `visibility`; only
 * the rows with `visibility` `list` are shown. Their requests use Responses with the plan's preview limits, which accept
 * function tools in a namespace, so each row has tool support. An account without Plus or Pro gets
 * `subscription_sharing_user_not_eligible` (403), which reaches the caller as QuotaOrPolicy with that code.
 */
class ChatGptPlanModels(private val http: ProviderHttp, private val auth: RequestAuth) : ModelDiscovery {
    private val json = Json { isLenient = true }

    override suspend fun list(): List<DiscoveredModel> {
        val reply = http.send(HttpRequest("GET", URL.toHttpUrl(), mapOf("Accept" to "application/json")), auth)
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        val models = body?.get("models") as? JsonArray ?: throw ProviderException(ProviderError.Malformed)
        return models.mapNotNull { row ->
            val model = row as? JsonObject ?: return@mapNotNull null
            if (model.text("visibility") != "list") return@mapNotNull null
            val slug = model.text("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DiscoveredModel(slug, model.text("display_name")?.takeIf { it.isNotBlank() } ?: slug, tools = true, style = RequestStyle.RESPONSES)
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val URL = "https://api.openai.com/v1/models"
    }
}
```

Create `core/providers/src/main/kotlin/dev/kestrel/providers/discovery/XaiModels.kt`:

```kotlin
package dev.kestrel.providers.discovery

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The model list of xAI's sign-in (design D13; research r31): `GET https://cli-chat-proxy.grok.com/v1/models` with the
 * sign-in's bearer and the choice's identity [headers]. Each row's `api_backend` gives its style: `responses` Responses,
 * `chat` Chat Completions, and `language` rows are dropped; `reasoning_efforts` gives its efforts. The rows are read from
 * `data`, or from `models` when there is no `data`.
 */
class XaiModels(private val http: ProviderHttp, private val auth: RequestAuth, private val headers: Map<String, String> = emptyMap()) : ModelDiscovery {
    private val json = Json { isLenient = true }

    override suspend fun list(): List<DiscoveredModel> {
        val reply = http.send(HttpRequest("GET", URL.toHttpUrl(), headers + ("Accept" to "application/json")), auth)
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        val rows = (body?.get("data") ?: body?.get("models")) as? JsonArray ?: throw ProviderException(ProviderError.Malformed)
        return rows.mapNotNull { row ->
            val model = row as? JsonObject ?: return@mapNotNull null
            val id = (model.text("id") ?: model.text("model"))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val style = when (model.text("api_backend")) {
                null, "responses" -> RequestStyle.RESPONSES
                "chat" -> RequestStyle.CHAT_COMPLETIONS
                else -> return@mapNotNull null
            }
            val efforts = (model["reasoning_efforts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            DiscoveredModel(
                id = id,
                name = id,
                reasoning = model.flag("supports_reasoning_effort") == true || !efforts.isNullOrEmpty(),
                reasoningEfforts = efforts?.takeIf { it.isNotEmpty() },
                contextWindow = model.number("context_window"),
                maxTokens = model.number("max_completion_tokens"),
                style = style,
            )
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private companion object {
        const val URL = "https://cli-chat-proxy.grok.com/v1/models"
    }
}
```

In `core/providers/src/main/kotlin/dev/kestrel/providers/client/TargetResolver.kt`, replace:

```kotlin
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CompatFlags
```

with:

```kotlin
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.CompatFlags
```

In `core/providers/src/main/kotlin/dev/kestrel/providers/client/TargetResolver.kt`, replace:

```kotlin
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.styles.AzureRouting
```

with:

```kotlin
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.signin.SignInKind
import dev.kestrel.providers.signin.SignInRegistry
import dev.kestrel.providers.styles.AzureRouting
```

In `core/providers/src/main/kotlin/dev/kestrel/providers/client/TargetResolver.kt`, add at the end of the file:

```kotlin

/** The default models of a new profile: model IDs, which for a local server may be the owner's own names. */
data class ModelDefaults(val lightweight: String?, val versatile: String?) {
    override fun toString(): String = "ModelDefaults(lightweight=${lightweight != null}, versatile=${versatile != null})"
}

/** The models that a profile offers for both roles, and the defaults among them (design D12 and D13). */
object OfferedModels {
    private val LISTED_ONLY = setOf(SignInKind.GITHUB, SignInKind.GITHUB_TENANT, SignInKind.CHATGPT_PLAN, SignInKind.XAI)

    /**
     * The rows that a profile of [providerId] with [choiceId] offers: for the sign-ins whose list is the account's own
     * (Copilot, the ChatGPT plan and xAI's sign-in) only the listed rows, each with the catalog's price, capabilities and
     * compat where the catalog has it; for every other choice the catalog's rows (with those that `overrides.json` adds,
     * such as Mantle's Claude rows) beside the listed ones. A row without tool support carries JSON actions.
     */
    fun rows(catalog: Catalog, providerId: String, choiceId: String, discovered: List<DiscoveredModel>): List<ModelEntry> {
        val provider = catalog.provider(providerId)
        val listed = discovered.map { ModelEntries.entryFor(provider, it.id, discovered) }
        if (SignInRegistry.kind(providerId, choiceId) in LISTED_ONLY) return listed
        return provider?.models.orEmpty().filter { row -> listed.none { it.id == row.id } } + listed
    }

    /**
     * The defaults among [rows]: for Copilot the first of its lightweight and of its versatile names that the rows offer
     * with tool calls, by ID or normalised name; for the others the catalog's utility model and default model when the
     * rows have them; else the first row for both.
     */
    fun defaults(catalog: Catalog, providerId: String, rows: List<ModelEntry>): ModelDefaults {
        val first = rows.firstOrNull()?.id ?: return ModelDefaults(null, null)
        val provider = catalog.provider(providerId)
        val copilot = catalog.overrides.copilot
        val light = if (providerId == CatalogLoader.COPILOT) byName(copilot.lightweight, rows) else rows.firstOrNull { it.id == provider?.utilityModel }?.id
        val versatile = if (providerId == CatalogLoader.COPILOT) byName(copilot.versatile, rows) else rows.firstOrNull { it.id == provider?.defaultModel }?.id
        return ModelDefaults(light ?: first, versatile ?: first)
    }

    private fun byName(names: List<String>, rows: List<ModelEntry>): String? =
        names.firstNotNullOfOrNull { name ->
            val wanted = CatalogLoader.normalise(name)
            rows.firstOrNull { it.toolMode == ToolMode.NATIVE && (CatalogLoader.normalise(it.name) == wanted || CatalogLoader.normalise(it.id) == wanted) }?.id
        }
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:providers:test`
Expected: `BUILD SUCCESSFUL`, with R₀ + 98 tests (R₀ + 80 + 18): `CopilotDomainTest` 2, `CopilotDiscoveryTest` 2, `CopilotAllowanceTest` 1, `CopilotModelsTest` 2, `CopilotHeadersTest` 2, `CopilotTargetsTest` 3, `ChatGptPlanModelsTest` 2, `XaiModelsTest` 1 and `OfferedModelsTest` 3.

- [ ] **Step 6: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
- Kestrel discovers the API address through `https://api.github.com/copilot_internal/user`. It accepts the returned address only if it is an `https://` address under `githubcopilot.com` (or the tenant's equivalent). `api.github.com` stays on the network allowlist, because Copilot needs it for this discovery and for token refresh.
- The model list comes from Copilot's own models endpoint.
- Request headers follow OpenClaw: the integration and editor identity headers, `X-Initiator`, `Openai-Intent`, and `Copilot-Vision-Request` when a screenshot is attached.
- Kestrel shows the remaining allowance wherever Copilot reports it, using a port of OpenClaw's usage code.
```

with:

```markdown
- Kestrel discovers the API address through `https://api.<domain>/copilot_internal/user`, with the GitHub token as the bearer; there is no token exchange, and the same token is the bearer of every Copilot request. It accepts the returned address only if it is an `https://` address whose host is `copilot-proxy.githubusercontent.com`, ends with `.githubcopilot.com`, or, for a tenant, is the tenant's own domain or under it; without an address it uses `https://api.individual.githubcopilot.com` or `https://copilot-api.<tenant>`. Discovery runs when the profile is created and before the first Copilot request in each process; a new address waits for the allowlist, and a network failure keeps the stored one. `api.github.com`, or `api.<tenant>` for a tenant, stays on the network allowlist for this discovery and the allowance.
- The model list comes from Copilot's own models endpoint. Kestrel shows a model when the model picker enables it, its policy is neither disabled nor unconfigured, and it is a chat model that streams, and offers it when it handles tool calls or the owner turns on JSON actions for it.
- Each model's request style follows the endpoints that it lists (`supported_endpoints`): Anthropic Messages is preferred for Claude models, Chat Completions for Gemini models and Responses otherwise, at `/messages`, `/chat/completions` and `/responses` under the discovered address. Claude models are sent without thinking blocks, and a Claude model that thinks by default gets a required tool call as `auto` (Claude Haiku 5.5 keeps it forced) and no temperature; every Claude model gets tool-call IDs that Anthropic accepts. Chat Completions uses Copilot's compatibility flags, and its usage is estimated when the stream carries none.
- Every request carries the runtime headers of `catalog/overrides.json` (VS Code's editor headers, `Openai-Organization` and `Copilot-Integration-Id`), `X-Initiator` (`agent` for the agent's later steps and after a tool result, `user` otherwise) and `Copilot-Vision-Request` when a screenshot is attached. Kestrel does not send `Openai-Intent`.
- "Check allowance" on the profile page shows the remaining premium and chat requests only when Copilot reports them as numbers. It sends the IDE headers of `catalog/overrides.json` with `Authorization: token`, as OpenClaw's usage code does. Under AI Credits these fields may be empty, and then the page shows only Kestrel's own estimate of this month's credits.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
| B27 | OpenClaw's Copilot sign-in uses client ID `Iv1.b507a08c87ecfe98`, the device flow with scope `read:user`, and endpoint discovery through `copilot_internal/user`. Its requests add `X-Initiator`, `Openai-Intent` and `Copilot-Vision-Request` headers. | `extensions/github-copilot/login.ts`, `runtime-auth.ts` and `stream.ts`, and `packages/ai/src/providers/github-copilot-headers.ts` in OpenClaw | ✔ |
```

with:

```markdown
| B27 | OpenClaw's Copilot sign-in uses client ID `Iv1.b507a08c87ecfe98`, the device flow with scope `read:user`, and endpoint discovery through `copilot_internal/user`. The GitHub token is the bearer of every request; no Copilot token is exchanged. Each model's request style follows its `supported_endpoints`. Its requests add `X-Initiator` and `Copilot-Vision-Request`; the stream code at the pinned commit does not set `Openai-Intent`. | `extensions/github-copilot/login.ts`, `runtime-auth.ts` and `stream.ts`, and `packages/ai/src/providers/github-copilot-headers.ts` in OpenClaw; research r27 | ✔ |
```

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`, with `:core:providers` R₀ + 98 and the other modules as before, lint without issues, and no audit finding.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
$main = 'core/providers/src/main/kotlin/dev/kestrel/providers'
$test = 'core/providers/src/test/kotlin/dev/kestrel/providers'
$fix = 'core/providers/src/test/resources/fixtures'
git add "$main/copilot/CopilotDomain.kt" "$main/copilot/CopilotDiscovery.kt" "$main/copilot/CopilotModels.kt" "$main/copilot/CopilotHeaders.kt" "$main/copilot/CopilotAllowance.kt" "$main/copilot/CopilotTargets.kt"
git add "$main/discovery/ChatGptPlanModels.kt" "$main/discovery/XaiModels.kt" "$main/client/TargetResolver.kt"
git add "$test/copilot/CopilotDomainTest.kt" "$test/copilot/CopilotDiscoveryTest.kt" "$test/copilot/CopilotAllowanceTest.kt" "$test/copilot/CopilotModelsTest.kt" "$test/copilot/CopilotHeadersTest.kt" "$test/copilot/CopilotTargetsTest.kt"
git add "$test/discovery/ChatGptPlanModelsTest.kt" "$test/discovery/XaiModelsTest.kt" "$test/client/OfferedModelsTest.kt"
git add "$fix/copilot/user.json" "$fix/copilot/models.json" "$fix/chatgpt-plan/models.json" "$fix/xai/models.json" docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(providers): add Copilot's discovery, models, headers, allowance and targets, and the account lists" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The Android side of sign-in

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/providers/AndroidLocalNetworkAccess.kt`, `LoopbackListenerAdapter.kt`, `CustomTabs.kt`, `SignInService.kt`, `ProviderGraph.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (at the 4C marker), `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `docs/superpowers/specs/2026-11-12-kestrel-design.md` (7.4, 10.4, 13.6 and 16.3)
- Test: the device tests `app/src/androidTest/kotlin/dev/kestrel/app/providers/ProviderEndToEndTest.kt` and `CustomTabSignInTest.kt`

**Interfaces:**
- Consumes: Tasks 1 to 5 (`CredentialManager`, `SignInRegistry`, `Entries`, the flows, `LoopbackServer`, `TargetResolver`, `ProfileClient`, `ProvidersRepository`, `OfferedModels`, the Copilot package, `ChatGptPlanModels`, `XaiModels`); plan 4A's `AppGraph.providerHttp`, `catalog`, `credentialVault`, `appliedProviders`, `networkPolicy` (`openTemporaryHosts`, `openTemporaryLocalServers`), `SettingKeys.PROVIDERS`, `SPENDING` and `providerModels`, `ProvidersSetting`, `ModelList`, `Spending`, `ProfileHosts`; plan 4B's `Adapters.forStyle(style, http)` and `Discoveries.forProvider`.
- Produces (package `dev.kestrel.app.providers`): `sealed interface SignInState { Idle; Waiting(providerId, choiceId, prompt: DeviceCodePrompt?); Succeeded(providerId, choiceId, result: SignInResult); Failed(providerId, choiceId, failure: SignInFailure?, error: ProviderError?) }`; `class CheckResult(val draft: Profile, val rows: List<ModelEntry>, val discovered: List<DiscoveredModel>, val defaults: ModelDefaults)`; `internal class ProviderGraph(context, scope, settings: SettingsStore, loadCatalog: () -> Catalog, http: ProviderHttp, vault: CredentialVault, applied: StateFlow<ProvidersSetting?>, policy: NetworkPolicy, report: (String, Throwable) -> Unit, loopback: LoopbackListener = LoopbackListenerAdapter(), localNetwork: LocalNetworkAccess = AndroidLocalNetworkAccess(context), clock: () -> Long = …)` with `catalog`, `credentials`, `stored: StateFlow<ProvidersSetting?>`, `resolver`, `repository`, `fun adapter(style)`, `suspend fun addSpending(profileId, month, credits, tokens)`, `spending: Flow<Spending>`, `providerReady: Flow<Boolean>`, `signIn: StateFlow<SignInState>`, `browserPage: StateFlow<String?>`, `tabOpened`, `settingsResumed`, `fun pageOpened()`, `fun reopenPage()`, `fun startSignIn(providerId, choiceId, tenant: String? = null, consent: Boolean = false, newRegistration: Boolean = false)`, `suspend fun signInAgain(profileId: String)`, `fun timeOutSignIn()`, `fun cancelSignIn()`, `fun leaveAddFlow()`, `suspend fun check(draft: Profile, credential: Credential?): CheckResult`, `suspend fun save(draft, credential, discovered, makeDefault): Profile`, `suspend fun delete(profileId): Boolean`, `setDefault`, `rename`, `setModel`, `setSwitch`, `replaceCredential`, `models`, `refreshModels`, `allowance`, and `companion object { const val PROVIDERS_PAGE = "PROVIDERS" }`; `internal class SignInService : Service` with `companion object { fun start(context, providerLabel); fun returnToKestrel(context); fun stop(context) }`; `internal object CustomTabs { const val SERVICE_ACTION; fun open(activity: Activity, url: String): Boolean; fun chrome(context: Context): String? }`; `internal class AndroidLocalNetworkAccess(context) : LocalNetworkAccess` with `companion object { const val PERMISSION; const val LOCAL_NETWORK_API = 37 }`; `internal class LoopbackListenerAdapter(server: LoopbackServer = LoopbackServer()) : LoopbackListener`. In `:app`: `AppGraph.providers: ProviderGraph` (internal), `MainActivity.EXTRA_RESUME_ADD`, and `ACCESS_LOCAL_NETWORK` in `MainActivity.ASKABLE` from Android 17 on.

Design D9, D10, D11, D13, D15 and D16; prototype P5. `ProviderGraph` wires the providers' part of Kestrel at plan 4A's marker. A sign-in runs in `AppGraph.scope` inside `SignInService`, a `dataSync` foreground service with the notification "Signing in to <provider>" and Cancel, which the graph starts before the page opens a tab or shows a code; each flow's hosts are leased while it runs. The browser's pages open in a Custom Tab built by hand (`ACTION_VIEW` with the session extra set to a null binder), from the settings activity without `FLAG_ACTIVITY_NEW_TASK`, so that the tab is in Kestrel's task. When the flow has the credential, a tab was opened, and the settings are not in front, `SignInService` starts `MainActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`, `EXTRA_PAGE = PROVIDERS` and `EXTRA_RESUME_ADD`. The new credential stays in the graph's memory (`CredentialManager.hold`) until the owner saves the profile. The graph keeps the ChatGPT plan's host ID, writes `ProvidersSetting.chatgptRegistration` after a sign-in that returned one, keeps a failed sign-in's issued client ID for "Try again" in the same add flow, and clears the kept registration only when a ChatGPT plan profile's token has lost its client ID (D9's `invalid_client`), not when a credential is missing. "Check" runs D9's check through a temporary lease on the profile's hosts (and `openTemporaryLocalServers` for a local server); Copilot's check discovers its base first, and Vertex's check calls `refresh()`, which always exchanges the key at Google, because plan 4B's shared token cache could otherwise answer for a new key file of an account that the process already holds. `SignInService` leaves the foreground and stops whenever the sign-in ends, in any state, also when it ended before the service saw it wait (a GHE.com tenant that is not one fails before the service starts), and a cancel of a waiting sign-in tells the service to stop too; on Android 15 and later it implements `onTimeout`, which ends the sign-in with "Android ended the sign-in, because it ran for too long. Start it again." A sign-in that throws an exception that no flow names ends as `EXCHANGE_FAILED` and is logged by its classes. A save ends the add flow's sign-in, so the next "Add a profile" signs in again. "Sign in again" on a GHE.com tenant's profile uses the tenant of its token, and forgets Copilot's base for the process. Saving writes the credential, the model list and the profile; deleting a ChatGPT plan profile revokes its refresh token first, while `auth.openai.com` is still allowed. `ProviderEndToEndTest` builds its own gateway, allowlist and setting, so it never touches the owner's profiles; `CustomTabSignInTest` uses Kestrel's real activity and `LoopbackServer` with test-only values, and its redirect case runs only with `kestrelRedirectCheck=true`.

- [ ] **Step 1: Write the failing device tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/providers/ProviderEndToEndTest.kt`:

```kotlin
package dev.kestrel.app.providers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.client.PlanLimits
import dev.kestrel.providers.client.ProfileClient
import dev.kestrel.providers.client.TargetResolver
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.styles.Adapters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A provider request on the device through the real network gateway (design D19): a profile with test-only names for a
 * "local server" at a MockWebServer on the device, its allowlist, its network log without bodies, a host off the list,
 * its price and the month's spending, and a 401 handled by the rule of one refresh. It builds its own gateway, allowlist
 * and setting, so the owner's profiles, credentials and spending are never touched.
 */
@RunWith(AndroidJUnit4::class)
class ProviderEndToEndTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<NetworkEvent>()
    private val policy = NetworkPolicy()
    private val gateway = NetworkGateway(policy) { events += it }
    private val http = ProviderHttp(gateway.callFactory, classify = { if (it is BlockedRequestException) ProviderError.NotAllowed else null })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val spent = CopyOnWriteArrayList<Double>()
    private val secret = "test-secret-prompt"
    private val request = ChatRequest("You are a test.", listOf(ChatMessage.User(listOf(Part.Text(secret)))), maxOutputTokens = 16)

    private val vault = object : CredentialVault {
        override suspend fun read(id: String): Credential? = null

        override suspend fun write(id: String, credential: Credential) = Unit

        override suspend fun delete(id: String) = Unit
    }

    private object NoLimits : PlanLimits {
        override fun reached(profileId: String) = Unit

        override fun cleared(profileId: String) = Unit
    }

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
        scope.cancel()
    }

    private fun profile(base: String) = Profile(
        "test-local", "Test", "lmstudio", "lmstudio-server", AuthMethod.SERVER_ADDRESS, null, baseUrl = base, localServer = LocalServerKind.LM_STUDIO,
        lightweight = ProfileModel("test-model", price = Price(input = 1.0, output = 2.0)), versatile = ProfileModel("test-model"),
    )

    private fun client(profile: Profile): ProfileClient {
        val resolver = TargetResolver(graph.catalog, CredentialManager(vault, http, graph.catalog, scope))
        return ProfileClient(
            profile, graph.catalog, MutableStateFlow(ProvidersSetting(listOf(profile))), resolver::resolve, { Adapters.forStyle(it, http) },
            { true }, spend = { _, _, credits, _ -> spent += credits }, limits = NoLimits,
        )
    }

    @Test
    fun aRequestToAnAllowedLocalServerIsPricedAndLoggedWithoutItsBody() = runBlocking {
        val profile = profile(server.url("/v1").toString())
        policy.setLocalServers(ProfileHosts.localServers(profile))
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500,\"total_tokens\":1500}}\n\n" +
                    "data: [DONE]\n\n",
            ).build(),
        )

        val priced = client(profile).complete(ModelRole.LIGHTWEIGHT, request)

        assertTrue(priced.cost.credits > 0)
        assertEquals(listOf(priced.cost.credits), spent.toList())
        assertTrue(server.takeRequest().body!!.utf8().contains(secret))
        val logged = events.single()
        assertEquals("POST", logged.method)
        assertEquals(server.url("/").host, logged.host)
        assertEquals("/v1/chat/completions", logged.path)
        assertEquals(200, logged.status)
        assertTrue(events.none { secret in it.toString() })
    }

    @Test
    fun aHostOffTheAllowlistIsRefusedBeforeAnyLookup() = runBlocking {
        val profile = profile("https://not-allowed.invalid/v1")

        try {
            client(profile).complete(ModelRole.LIGHTWEIGHT, request)
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.NotAllowed, e.error)
        }
        assertEquals("not-allowed.invalid", events.single().host)
        assertTrue(events.single().detail.orEmpty().contains("not on the allowlist"))
    }

    @Test
    fun a401IsRefreshedOnceAndSentAgain() = runBlocking {
        policy.setLocalServers(setOf(server.url("/").host))
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val auth = object : RequestAuth {
            var token = "test-token-1"
            var refreshes = 0

            override suspend fun headers(request: HttpRequest) = mapOf("Authorization" to "Bearer $token")

            override suspend fun refresh(): RefreshResult {
                refreshes++
                token = "test-token-2"
                return RefreshResult.Refreshed
            }
        }

        val reply = http.send(HttpRequest("GET", server.url("/v1/models")), auth)

        assertEquals(200, reply.status)
        assertEquals(1, auth.refreshes)
        assertEquals("Bearer test-token-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer test-token-2", server.takeRequest().headers["Authorization"])
        assertEquals(listOf(401, 200), events.map { it.status })
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/providers/CustomTabSignInTest.kt`:

```kotlin
package dev.kestrel.app.providers

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.privacy.network.LoopbackServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Custom Tab of browser sign-in with the device's own Chrome (design D10 and D19; prototype P5): the tab opens in
 * Kestrel's task, SignInService runs in the foreground while the tab is in front, the loopback server receives the
 * callback, and the settings come back on top. Android holds an instrumented process at the foreground-service level
 * itself, so the test checks the service rather than Kestrel's importance; the freezer and the return to the front of a
 * real sign-in are checked by P5 and by the session's sign-in on the A71. The redirect case through postman-echo.com,
 * which repeats P5's q1 and q3, runs only with the instrumentation argument `kestrelRedirectCheck=true`, so that normal
 * runs never depend on an outside site. Chrome must have finished its first-run screens.
 */
@RunWith(AndroidJUnit4::class)
class CustomTabSignInTest {
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val executor = Executors.newSingleThreadExecutor()
    private val sessions = mutableListOf<LoopbackServer.Session>()

    /** Whether a test opened a tab, so that Chrome is ended only then. */
    private var tabOpened = false

    @Before
    fun needsChrome() {
        assertNotNull("Chrome with its Custom Tabs service is needed on this device", CustomTabs.chrome(context))
    }

    @After
    fun cleanUp() {
        sessions.forEach { it.close() }
        executor.shutdownNow()
        SignInService.stop(context)
        if (tabOpened) shell("am force-stop com.android.chrome")
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }.also { descriptor.close() }
        }

    /** The task of each activity record in `dumpsys activity activities`, by its component. */
    private fun tasks(): Map<String, String> =
        Regex("""ActivityRecord\{\S+ u\d+ (\S+/\S+) t(\d+)""").findAll(shell("dumpsys activity activities"))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        fail("$what did not happen within 20 s")
    }

    private fun waitForTab() = waitFor("Chrome's Custom Tab") {
        val dump = shell("dumpsys activity activities")
        if ("FirstRunActivity" in dump) fail("Chrome shows its first-run screens: open Chrome on this device once and finish them")
        dump.lines().any { "topResumedActivity" in it && CUSTOM_TAB in it }
    }

    private fun open(url: String) {
        tabOpened = true
        activity.scenario.onActivity { assertTrue("no browser", CustomTabs.open(it, url)) }
    }

    private fun listen(ipv6: Boolean, port: Int? = null): Pair<LoopbackServer.Session, java.util.concurrent.Future<Map<String, String>?>> {
        val session = LoopbackServer().open(ipv6, port, "/auth/callback") ?: error("the loopback port is busy")
        sessions += session
        return session to executor.submit<Map<String, String>?> { session.await("test-state", 60_000) }
    }

    @Test
    fun theTabOpensInKestrelsTaskKestrelStaysInTheForegroundAndTheSettingsComeBack() {
        SignInService.start(context, "Test")
        val (session, callback) = listen(ipv6 = false)

        open("http://127.0.0.1:${session.port}/auth/callback?code=test-code&state=test-state")
        waitForTab()

        val tasks = tasks()
        assertNotNull("Chrome's Custom Tab in dumpsys", tasks[CUSTOM_TAB])
        assertEquals("the Custom Tab is in Kestrel's task", tasks[MAIN], tasks[CUSTOM_TAB])
        // Android holds an instrumented process at the foreground-service level itself, so the test checks the service.
        val service = shell("dumpsys activity services dev.kestrel/.app.providers.SignInService")
        assertTrue("SignInService runs in the foreground", "isForeground=true" in service)
        assertEquals("test-code", callback.get(30, TimeUnit.SECONDS)?.get("code"))

        SignInService.returnToKestrel(context)
        waitFor("Kestrel's settings on top") { shell("dumpsys activity activities").lines().any { "topResumedActivity" in it && MAIN in it } }
    }

    @Test
    fun aSignInThatEndsBeforeTheServiceSawItWaitStopsTheService() {
        val providers = (context.applicationContext as dev.kestrel.app.KestrelApplication).graph.providers
        val service = { shell("dumpsys activity services dev.kestrel/.app.providers.SignInService") }
        SignInService.start(context, "Test")
        waitFor("SignInService in the foreground") { "isForeground=true" in service() }
        // A GHE.com tenant that is not one fails at once, without a request (design D10).
        providers.startSignIn("github-copilot", "github-copilot-enterprise", tenant = "not a tenant")
        try {
            waitFor("SignInService to stop") { "isForeground=true" !in service() }
        } finally {
            providers.cancelSignIn()
        }
    }

    @Test
    fun aRedirectFromAPublicPageReachesTheLoopbackServerWithoutAPrompt() {
        assumeTrue("Runs only with kestrelRedirectCheck=true", InstrumentationRegistry.getArguments().getString("kestrelRedirectCheck") == "true")
        SignInService.start(context, "Test")
        for ((ipv6, host) in listOf(false to "127.0.0.1", true to "localhost")) {
            val (session, callback) = listen(ipv6)
            val target = "http://$host:${session.port}/auth/callback?code=test-code&state=test-state"
            open("https://postman-echo.com/redirect-to?url=" + URLEncoder.encode(target, "UTF-8"))
            // Chrome's first-run screens give a clear message here too, not a timeout.
            waitForTab()

            assertEquals("the redirect to $host", "test-code", callback.get(45, TimeUnit.SECONDS)?.get("code"))
            shell("am force-stop com.android.chrome")
        }
    }

    private companion object {
        const val MAIN = "dev.kestrel/.app.MainActivity"
        const val CUSTOM_TAB = "com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity"
    }
}
```

- [ ] **Step 2: Compile the device tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, because `CustomTabSignInTest` does not compile: "Unresolved reference 'CustomTabs'" and "Unresolved reference 'SignInService'".

- [ ] **Step 3: Write the local network check, the loopback listener and the Custom Tab**

Create `app/src/main/kotlin/dev/kestrel/app/providers/AndroidLocalNetworkAccess.kt`:

```kotlin
package dev.kestrel.app.providers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.kestrel.providers.client.LocalNetworkAccess

/**
 * Whether Kestrel may reach a server on the owner's network (design D11): from Android 17 on, an app that targets API 37
 * needs ACCESS_LOCAL_NETWORK for it; earlier Android versions, such as the A71's Android 13, have no such permission.
 */
internal class AndroidLocalNetworkAccess(private val context: Context) : LocalNetworkAccess {
    override fun allowed(host: String): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_API || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Android 17, whose local-network permission applies to apps that target it (research r40). */
        const val LOCAL_NETWORK_API = 37
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/providers/LoopbackListenerAdapter.kt`:

```kotlin
package dev.kestrel.app.providers

import dev.kestrel.privacy.network.LoopbackServer
import dev.kestrel.providers.signin.LoopbackAddresses
import dev.kestrel.providers.signin.LoopbackCallback
import dev.kestrel.providers.signin.LoopbackListener
import dev.kestrel.providers.signin.LoopbackSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `:core:providers`' loopback listener over `:core:privacy`'s LoopbackServer, which alone may open a socket (design D1
 * and D10). A cancelled wait closes the server, so that its blocking wait ends at once.
 */
internal class LoopbackListenerAdapter(private val server: LoopbackServer = LoopbackServer()) : LoopbackListener {
    override suspend fun open(addresses: LoopbackAddresses, port: Int?, path: String): LoopbackSession? =
        withContext(Dispatchers.IO) { server.open(addresses == LoopbackAddresses.BOTH, port, path) }?.let(::Session)

    private class Session(private val session: LoopbackServer.Session) : LoopbackSession {
        override val port: Int = session.port

        override suspend fun await(expectedState: String, timeoutMillis: Long): LoopbackCallback? = coroutineScope {
            // Started at once, so that a cancellation before it runs still reaches its finally and closes the server.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    session.close()
                }
            }
            try {
                withContext(Dispatchers.IO) { session.await(expectedState, timeoutMillis) }?.let(::LoopbackCallback)
            } finally {
                closer.cancel()
            }
        }

        override fun close() = session.close()
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/providers/CustomTabs.kt`:

```kotlin
package dev.kestrel.app.providers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.net.toUri

/**
 * Opens a sign-in page in a Custom Tab, built by hand so that no library ships (design D10): `ACTION_VIEW` with the
 * session extra set to a null binder, Chrome when Chrome offers the Custom Tabs service, else the default browser. It is
 * started from Kestrel's settings activity without FLAG_ACTIVITY_NEW_TASK, so that the tab is in Kestrel's task: closing
 * the tab returns to the page, and SignInService may bring Kestrel back to the front after the callback (prototype P5).
 */
internal object CustomTabs {
    const val SERVICE_ACTION = "android.support.customtabs.action.CustomTabsService"
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
    private const val CHROME = "com.android.chrome"

    /** Opens [url]; false when the phone has no browser. */
    fun open(activity: Activity, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
        chrome(activity)?.let { intent.setPackage(it) }
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Chrome's package when it offers the Custom Tabs service, found through the manifest's `<queries>` entry. */
    fun chrome(context: Context): String? =
        context.packageManager.queryIntentServices(Intent(SERVICE_ACTION), PackageManager.ResolveInfoFlags.of(0))
            .map { it.serviceInfo.packageName }
            .firstOrNull { it == CHROME }
}
```

- [ ] **Step 4: Write the sign-in service and the provider graph**

Create `app/src/main/kotlin/dev/kestrel/app/providers/SignInService.kt`:

```kotlin
package dev.kestrel.app.providers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.MainActivity
import dev.kestrel.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps Kestrel's process in the foreground while a sign-in runs (design D10): without it, Android's freezer stops a
 * browser sign-in about 70 s after the tab opens, and nothing in the tab shows it (prototype P5, q4). It is a `dataSync`
 * service, because `shortService`'s 3 minutes are shorter than a browser callback's 5 and a device code's 15. The
 * settings page starts it before it opens the tab or shows the code; it stops when the sign-in ends, fails or is
 * cancelled, and its notification "Signing in to <provider>" has a Cancel action. When the sign-in succeeded after Kestrel
 * opened a Custom Tab and the settings are not in front, it brings the settings back: P5 showed that Android allows this
 * start while the tab is in Kestrel's task (q6).
 */
internal class SignInService : Service() {
    private val graph get() = (application as KestrelApplication).graph
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                graph.providers.cancelSignIn()
                stop()
                return START_NOT_STICKY
            }
            ACTION_RETURN -> {
                returnToKestrel()
                stop()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }
        }
        try {
            startForeground(NOTIFICATION_ID, notification(intent?.getStringExtra(EXTRA_PROVIDER).orEmpty()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // For example a start that Android does not allow from the background; the sign-in goes on without it.
            graph.report("Starting the sign-in service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) watcher = graph.scope.launch(Dispatchers.Main) { watch() }
        return START_NOT_STICKY
    }

    /**
     * Leaves the foreground and stops when the sign-in ends, and brings Kestrel back after a successful one. A sign-in can
     * end before this service sees it wait, as without network, so a success or a failure always stops the service; Idle
     * (a cancel) stops it only after a wait, so that a start without a sign-in, as CustomTabSignInTest's, keeps it.
     */
    private suspend fun watch() {
        var waited = false
        graph.providers.signIn.collect { state ->
            when (state) {
                is SignInState.Waiting -> waited = true
                is SignInState.Succeeded -> {
                    if (graph.providers.tabOpened && !graph.providers.settingsResumed) returnToKestrel()
                    stop()
                }
                is SignInState.Failed -> stop()
                SignInState.Idle -> if (waited) stop()
            }
        }
    }

    // Android 15 and later allow a dataSync service 6 hours a day and then call this; a sign-in never needs that long, so
    // it ends with a sentence that says why, and the service stops before Android would end Kestrel.
    override fun onTimeout(startId: Int, fgsType: Int) {
        graph.providers.timeOutSignIn()
        stop()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    private fun returnToKestrel() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_PAGE, ProviderGraph.PROVIDERS_PAGE)
            .putExtra(MainActivity.EXTRA_RESUME_ADD, true)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            // A later Android or Chrome may refuse the start; the loopback page tells the owner to close the tab.
            graph.report("Returning to Kestrel after a sign-in", e)
        }
    }

    private fun stop() {
        watcher?.cancel()
        watcher = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(provider: String): Notification {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.signin_channel), NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, SignInService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.signin_notification, provider))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.signin_cancel), cancel).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "sign-in"
        private const val NOTIFICATION_ID = 7_105
        private const val EXTRA_PROVIDER = "dev.kestrel.app.extra.PROVIDER"
        private const val ACTION_CANCEL = "dev.kestrel.app.action.CANCEL_SIGN_IN"
        private const val ACTION_RETURN = "dev.kestrel.app.action.RETURN_AFTER_SIGN_IN"
        private const val ACTION_STOP = "dev.kestrel.app.action.STOP_SIGN_IN"

        /** Starts the service for a sign-in to [provider], from the settings activity, which is in front. */
        fun start(context: Context, provider: String) {
            context.startForegroundService(Intent(context, SignInService::class.java).putExtra(EXTRA_PROVIDER, provider))
        }

        /** Brings Kestrel's settings back to the front from the running service, as after a sign-in in a Custom Tab. */
        fun returnToKestrel(context: Context) {
            context.startService(Intent(context, SignInService::class.java).setAction(ACTION_RETURN))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SignInService::class.java).setAction(ACTION_STOP))
        }
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/providers/ProviderGraph.kt`:

```kotlin
package dev.kestrel.app.providers

import android.content.Context
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.providerModels
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingsStore
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.client.LocalNetworkAccess
import dev.kestrel.providers.client.ModelDefaults
import dev.kestrel.providers.client.OfferedModels
import dev.kestrel.providers.client.PlanLimits
import dev.kestrel.providers.client.ProfileClient
import dev.kestrel.providers.client.ProvidersRepository
import dev.kestrel.providers.client.TargetResolver
import dev.kestrel.providers.copilot.CopilotAllowance
import dev.kestrel.providers.copilot.CopilotDiscovery
import dev.kestrel.providers.copilot.CopilotModels
import dev.kestrel.providers.copilot.CopilotQuota
import dev.kestrel.providers.copilot.CopilotTargets
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.discovery.ChatGptPlanModels
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.discovery.Discoveries
import dev.kestrel.providers.discovery.ModelList
import dev.kestrel.providers.discovery.XaiModels
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.profiles.ChatGptRegistration
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.BrowserFlow
import dev.kestrel.providers.signin.ChatGptPlanFlow
import dev.kestrel.providers.signin.CheckMethod
import dev.kestrel.providers.signin.DeviceCodeFlow
import dev.kestrel.providers.signin.DeviceCodePrompt
import dev.kestrel.providers.signin.Entries
import dev.kestrel.providers.signin.GitHubDeviceFlow
import dev.kestrel.providers.signin.LoopbackListener
import dev.kestrel.providers.signin.MiniMaxDeviceFlow
import dev.kestrel.providers.signin.MiniMaxRegion
import dev.kestrel.providers.signin.OpenRouterBrowserFlow
import dev.kestrel.providers.signin.SignInFailed
import dev.kestrel.providers.signin.SignInFailure
import dev.kestrel.providers.signin.SignInKind
import dev.kestrel.providers.signin.SignInRegistry
import dev.kestrel.providers.signin.SignInResult
import dev.kestrel.providers.signin.XaiDeviceFlow
import dev.kestrel.providers.styles.Adapters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.util.UUID

/** Where a sign-in stands, for the sign-in page and SignInService (design D9 and D10). */
sealed interface SignInState {
    data object Idle : SignInState

    /** A sign-in runs; [prompt] is the device code once the provider gave it. */
    data class Waiting(val providerId: String, val choiceId: String, val prompt: DeviceCodePrompt? = null) : SignInState

    data class Succeeded(val providerId: String, val choiceId: String, val result: SignInResult) : SignInState

    /** [failure] is a reason that the page explains; [error] a transport or HTTP failure. */
    data class Failed(val providerId: String, val choiceId: String, val failure: SignInFailure?, val error: ProviderError?) : SignInState
}

/** What a check found before the owner saves the profile (design D9 and D13). Model names can be the owner's own. */
class CheckResult(val draft: Profile, val rows: List<ModelEntry>, val discovered: List<DiscoveredModel>, val defaults: ModelDefaults) {
    override fun toString(): String = "CheckResult(draft=$draft, rows=${rows.size}, discovered=${discovered.size})"
}

/**
 * The providers' part of Kestrel's graph (design D9 to D13, D15 and D18): the credential manager, the clients that
 * sub-project 5 uses, the sign-ins with their leases on the allowlist, the add flow's check, and the changes that the
 * Providers page makes. A new credential stays in memory until the owner saves its profile; Kestrel forgets it when the
 * owner leaves the add flow or its process ends. Changes of the stored setting are made one at a time.
 */
internal class ProviderGraph(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    loadCatalog: () -> Catalog,
    val http: ProviderHttp,
    vault: CredentialVault,
    val applied: StateFlow<ProvidersSetting?>,
    private val policy: NetworkPolicy,
    private val report: (String, Throwable) -> Unit,
    private val loopback: LoopbackListener = LoopbackListenerAdapter(),
    val localNetwork: LocalNetworkAccess = AndroidLocalNetworkAccess(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val catalog: Catalog by lazy(loadCatalog)
    val credentials: CredentialManager by lazy { CredentialManager(vault, http, catalog, scope, clock) }
    private val writes = Mutex()

    /** The stored provider setting, null until the store has answered. */
    val stored: StateFlow<ProvidersSetting?> = settings.observe(SettingKeys.PROVIDERS)
        .catch { report("Reading the provider setting", it) }
        .stateIn<ProvidersSetting?>(scope, SharingStarted.Eagerly, null)

    private val copilot by lazy { CopilotTargets(catalog, credentials, CopilotDiscovery(http)) { profile, base -> moveCopilotBase(profile, base) } }

    val resolver: TargetResolver by lazy {
        TargetResolver(catalog, credentials, { settings.get(SettingKeys.providerModels(it)).models }, mapOf(CatalogLoader.COPILOT to copilot))
    }

    /** What sub-project 5 asks for a profile's client (design D13). */
    val repository: ProvidersRepository by lazy { ProvidersRepository(stored, applied, ::client) }

    /** The adapter of a style; the debug hooks replace nothing here. */
    fun adapter(style: RequestStyle): ChatAdapter = Adapters.forStyle(style, http)

    private fun client(profile: Profile, limits: PlanLimits) =
        ProfileClient(profile, catalog, applied, resolver::resolve, ::adapter, localNetwork, ::addSpending, limits, clock)

    /** Adds a call's credits and tokens to the month's spending (design D14). */
    suspend fun addSpending(profileId: String, month: String, credits: Double, tokens: Long) = writes.withLock {
        settings.set(SettingKeys.SPENDING, settings.get(SettingKeys.SPENDING).add(profileId, month, credits, tokens))
    }

    val spending: Flow<Spending> = settings.observe(SettingKeys.SPENDING)

    /**
     * Whether the setup checklist's step is done (design D18): a default profile exists, its credential exists (or it is a
     * local server without a key), and its borrowed sign-in is not switched off.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val providerReady: Flow<Boolean> = stored.filterNotNull().mapLatest { setting ->
        val profile = setting.defaultProfile ?: return@mapLatest false
        val credential = profile.credentialId?.let { credentials.read(it) }
        setting.usable(profile, catalog) && (credential != null || profile.credentialId == null)
    }

    // The sign-ins.

    private val signInState = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = signInState.asStateFlow()

    private val pages = MutableStateFlow<String?>(null)

    /** A sign-in page that the settings activity opens in a Custom Tab, then clears with [pageOpened]. */
    val browserPage: StateFlow<String?> = pages.asStateFlow()

    /** Whether Kestrel opened a Custom Tab for the running sign-in. */
    @Volatile
    var tabOpened = false
        private set

    /** Whether the settings activity is in front, which MainActivity sets in onResume and onPause. */
    @Volatile
    var settingsResumed = false

    private var job: Job? = null

    /** The ChatGPT plan's issued client ID that a failed sign-in of this add flow returned, for "Try again" (design D9). */
    private var pendingRegistration: ChatGptRegistration? = null

    /**
     * The settings activity opened a page of the running sign-in in a Custom Tab: the browser sign-in's page, or the device
     * code's verification address. After the sign-in, SignInService brings the settings back when they are not in front.
     */
    fun pageOpened() {
        pages.value = null
        if (signInState.value is SignInState.Waiting) tabOpened = true
    }

    /** The running browser sign-in's page, so that the owner can open it again after he closed its tab. */
    private var lastPage: String? = null

    /** Asks the settings activity to open the running browser sign-in's page again. */
    fun reopenPage() {
        if (signInState.value is SignInState.Waiting) lastPage?.let { pages.value = it }
    }

    /**
     * Starts the sign-in of [choiceId] on [providerId] and SignInService. [tenant] is a GHE.com tenant for Copilot's
     * enterprise choice; [consent] asks again for ChatGPT plan use; [newRegistration] registers Kestrel with another
     * ChatGPT account, replacing the kept registration.
     */
    fun startSignIn(providerId: String, choiceId: String, tenant: String? = null, consent: Boolean = false, newRegistration: Boolean = false) {
        val kind = SignInRegistry.kind(providerId, choiceId) ?: return
        job?.cancel()
        tabOpened = false
        pages.value = null
        // A GHE.com tenant that is not one fails before the service starts.
        if (kind == SignInKind.GITHUB_TENANT && tenant?.let(GitHubDeviceFlow::tenant) == null) {
            signInState.value = SignInState.Failed(providerId, choiceId, SignInFailure.UNTRUSTED_ADDRESS, null)
            return
        }
        signInState.value = SignInState.Waiting(providerId, choiceId)
        SignInService.start(context, catalog.provider(providerId)?.label ?: providerId)
        job = scope.launch {
            signInState.value = try {
                SignInState.Succeeded(providerId, choiceId, runFlow(kind, tenant, consent, newRegistration))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInFailed) {
                // OpenAI refused this add flow's issued client ID, or it is another account's: the next sign-in registers again.
                if (e.kind == SignInFailure.REGISTRATION_REFUSED || e.kind == SignInFailure.OTHER_ACCOUNT) pendingRegistration = null
                keep(e)
                SignInState.Failed(providerId, choiceId, e.kind, null)
            } catch (e: ProviderException) {
                SignInState.Failed(providerId, choiceId, null, e.error)
            } catch (e: Exception) {
                // A failure that no flow names: the sign-in ends instead of waiting for ever. Logged by its classes only.
                report("A sign-in", e)
                SignInState.Failed(providerId, choiceId, SignInFailure.EXCHANGE_FAILED, null)
            }
        }
    }

    /**
     * "Sign in again" on a profile's page: the sign-in of its choice, on the GHE.com tenant of its GitHub token for
     * Copilot's enterprise choice.
     */
    suspend fun signInAgain(profileId: String) {
        val profile = stored.value?.profiles?.firstOrNull { it.id == profileId } ?: return
        val tenant = (profile.credentialId?.let { credentials.read(it) } as? Credential.GitHubToken)?.domain
        startSignIn(profile.providerId, profile.choiceId, tenant)
    }

    /** Android ended SignInService after its time (Android 15 and later): the sign-in ends with a clear sentence. */
    fun timeOutSignIn() {
        val waiting = signInState.value as? SignInState.Waiting ?: return
        job?.cancel()
        job = null
        pages.value = null
        signInState.value = SignInState.Failed(waiting.providerId, waiting.choiceId, SignInFailure.TIMED_OUT, null)
    }

    /** Ends the running sign-in, as the page's and the notification's Cancel do. */
    fun cancelSignIn() {
        // The cancel can come before SignInService's watcher has seen the sign-in wait, so the service is told to stop too.
        val waiting = signInState.value is SignInState.Waiting
        job?.cancel()
        job = null
        pages.value = null
        lastPage = null
        signInState.value = SignInState.Idle
        if (waiting) {
            try {
                SignInService.stop(context)
            } catch (e: IllegalStateException) {
                // Android refuses the start only while Kestrel is in the background without the service: nothing runs then.
            }
        }
    }

    /** Forgets the add flow's sign-in and pending registration when the owner leaves the add flow. */
    fun leaveAddFlow() {
        cancelSignIn()
        pendingRegistration = null
    }

    private suspend fun runFlow(kind: SignInKind, tenant: String?, consent: Boolean, newRegistration: Boolean): SignInResult = when (kind) {
        SignInKind.GITHUB -> deviceCode(GitHubDeviceFlow(GitHubDeviceFlow.PUBLIC, http))
        SignInKind.GITHUB_TENANT -> deviceCode(GitHubDeviceFlow(tenant?.let(GitHubDeviceFlow::tenant) ?: throw IllegalArgumentException("Not a tenant"), http))
        SignInKind.XAI -> deviceCode(XaiDeviceFlow(http, { policy.openTemporaryHosts(it) }))
        SignInKind.MINIMAX_GLOBAL -> deviceCode(MiniMaxDeviceFlow(MiniMaxRegion.GLOBAL, http))
        SignInKind.MINIMAX_CHINA -> deviceCode(MiniMaxDeviceFlow(MiniMaxRegion.CHINA, http))
        SignInKind.CHATGPT_PLAN -> browser(ChatGptPlanFlow(chatgptHostId(), chatgptRegistration(newRegistration), consent, http))
        SignInKind.OPENROUTER -> browser(OpenRouterBrowserFlow(http))
    }.also { result -> result.registration?.let { registration -> update { it.copy(chatgptRegistration = registration) } } }

    private suspend fun deviceCode(flow: DeviceCodeFlow): SignInResult = policy.openTemporaryHosts(flow.signInHosts).use {
        val prompt = flow.start()
        signInState.value = (signInState.value as? SignInState.Waiting)?.copy(prompt = prompt) ?: signInState.value
        flow.await(prompt)
    }

    private suspend fun browser(flow: BrowserFlow): SignInResult = policy.openTemporaryHosts(flow.signInHosts).use {
        flow.run(loopback) { page ->
            lastPage = page
            pages.value = page
        }
    }

    /** A failure's registration: kept in the setting when it has the account's `sub`, else only for this add flow. */
    private suspend fun keep(failure: SignInFailed) {
        val registration = failure.registration ?: return
        if (registration.subject != null) update { it.copy(chatgptRegistration = registration) } else pendingRegistration = registration
    }

    /** The installation's opaque host ID for the ChatGPT plan, made once (design D9). */
    private suspend fun chatgptHostId(): String {
        settings.get(SettingKeys.PROVIDERS).chatgptHostId?.let { return it }
        val made = "urn:uuid:${UUID.randomUUID()}"
        update { if (it.chatgptHostId == null) it.copy(chatgptHostId = made) else it }
        return settings.get(SettingKeys.PROVIDERS).chatgptHostId ?: made
    }

    /**
     * The registration that a ChatGPT plan sign-in uses: the issued client ID that this add flow already got, also for
     * "Register with this account", so that one add flow never registers Kestrel twice; else none for "Register with this
     * account", which ignores only the kept registration; else the kept one, which is cleared first when a ChatGPT plan
     * profile's credential lost its client ID to `invalid_client`, so that the sign-in registers Kestrel again (design D9).
     */
    private suspend fun chatgptRegistration(newRegistration: Boolean): ChatGptRegistration? {
        // A client ID that OpenAI issued in this add flow is reused, also by "Register with this account" (design D9).
        pendingRegistration?.let { return it }
        if (newRegistration) return null
        val setting = settings.get(SettingKeys.PROVIDERS)
        // Only a token that lost its client ID to invalid_client clears it, not a missing or unreadable credential.
        val cleared = setting.profiles.filter { chatgptPlan(it) }.any { profile ->
            val token = profile.credentialId?.let { credentials.read(it) } as? Credential.OAuthToken
            token != null && token.clientId == null
        }
        if (cleared) {
            update { it.copy(chatgptRegistration = null) }
            return null
        }
        return setting.chatgptRegistration
    }

    private fun chatgptPlan(profile: Profile): Boolean = catalog.choice(profile.providerId, profile.choiceId)?.settings?.chatgptPlan == true

    // The add flow and the profile page.

    /**
     * The add flow's "Check" (design D9, D11 and D13): with the new [credential] held in memory and the future profile's
     * hosts leased on the allowlist (its local server through the local-server lease), a model discovery, Vertex's token
     * exchange, or one short request with the model that the owner chose first. Returns the offered rows and their
     * defaults, and the draft with what the check found (Copilot's API base and host). Throws ProviderException or
     * SignInFailed.
     */
    suspend fun check(draft: Profile, credential: Credential?): CheckResult {
        val held = credential?.let { credentials.hold(it) }
        val leases = mutableListOf<AutoCloseable>()
        try {
            var profile = draft.copy(credentialId = held ?: draft.credentialId)
            leases += policy.openTemporaryHosts(ProfileHosts.hosts(profile, catalog))
            ProfileHosts.localServers(profile).takeIf { it.isNotEmpty() }?.let { leases += policy.openTemporaryLocalServers(it) }
            val provider = catalog.provider(profile.providerId) ?: throw ProviderException(ProviderError.NotAllowed)
            val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
            val discovered = when (Entries.checkMethod(provider, choice)) {
                CheckMethod.DISCOVERY -> {
                    if (profile.providerId == CatalogLoader.COPILOT) {
                        val token = credentials.read(profile.credentialId!!) as? Credential.GitHubToken ?: throw ProviderException(ProviderError.SignInExpired(profile.providerId))
                        val base = CopilotDiscovery(http).apiBase(token)
                        leases += policy.openTemporaryHosts(setOf(base.host))
                        profile = profile.copy(baseUrl = base.toString(), discoveredHosts = (profile.discoveredHosts + base.host).distinct())
                    }
                    discover(profile) ?: run {
                        shortRequest(profile)
                        emptyList()
                    }
                }
                CheckMethod.TOKEN_EXCHANGE -> {
                    // Plan 4B's token cache could answer for a new key file of an account that the process already holds,
                    // so the check always exchanges the key at Google.
                    val auth = credentials.auth(profile, KeyHeader.BEARER)
                    when (val result = auth.refresh()) {
                        RefreshResult.Refreshed -> Unit
                        is RefreshResult.FailedForNow -> throw ProviderException(result.error)
                        RefreshResult.Expired, RefreshResult.NotPossible -> throw ProviderException(auth.rejected)
                    }
                    emptyList()
                }
                CheckMethod.SHORT_REQUEST -> {
                    shortRequest(profile)
                    emptyList()
                }
            }
            val rows = OfferedModels.rows(catalog, profile.providerId, profile.choiceId, discovered)
            return CheckResult(profile.copy(credentialId = draft.credentialId), rows, discovered, OfferedModels.defaults(catalog, profile.providerId, rows))
        } finally {
            leases.forEach { it.close() }
            held?.let(credentials::release)
        }
    }

    /** The profile's model list from its provider, or null when the provider has none (design D11 to D13). */
    private suspend fun discover(profile: Profile): List<DiscoveredModel>? {
        val provider = catalog.provider(profile.providerId) ?: return null
        val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
        val base = ProfileHosts.baseUrl(profile, catalog) ?: return null
        val style = choice?.style ?: provider.style ?: RequestStyle.CHAT_COMPLETIONS
        val auth = credentials.auth(profile, choice?.keyHeader ?: TargetResolver.defaultKeyHeader(style))
        return when {
            profile.providerId == CatalogLoader.COPILOT -> CopilotModels(http, base, auth, catalog.overrides.copilot.runtimeHeaders).list()
            SignInRegistry.kind(profile.providerId, profile.choiceId) == SignInKind.CHATGPT_PLAN -> ChatGptPlanModels(http, auth).list()
            SignInRegistry.kind(profile.providerId, profile.choiceId) == SignInKind.XAI -> XaiModels(http, auth, choice?.identityHeaders.orEmpty()).list()
            profile.localServer != null -> Discoveries.forLocalServer(profile.localServer!!, base, auth, http).list()
            else -> Discoveries.forProvider(provider, base, profile.region, auth, http)?.list()
        }
    }

    /** The check of a provider without a list: "Reply with OK." with the lightweight model (design D9). */
    private suspend fun shortRequest(profile: Profile) {
        val target = resolver.resolve(profile, ModelRole.LIGHTWEIGHT)
        adapter(target.style).complete(Entries.checkRequest(), target)
    }

    /**
     * Saves [draft] with its new [credential], if any, under the profile's credential ID or a new one, and its model list;
     * the first profile, or one that the owner marks, becomes the default. Kestrel forgets the add flow's pending
     * registration then.
     */
    suspend fun save(draft: Profile, credential: Credential?, discovered: List<DiscoveredModel>, makeDefault: Boolean): Profile {
        val id = credential?.let { credentials.store(it, draft.credentialId) } ?: draft.credentialId
        val signedIn = draft.method == AuthMethod.DEVICE_CODE || draft.method == AuthMethod.BROWSER
        val profile = draft.copy(credentialId = id, signedInAt = if (credential != null && signedIn) clock() else draft.signedInAt)
        settings.set(SettingKeys.providerModels(profile.id), ModelList(discovered, clock()))
        update { setting ->
            val profiles = setting.profiles.filterNot { it.id == profile.id } + profile
            setting.copy(profiles = profiles, defaultProfileId = if (makeDefault || setting.defaultProfile == null) profile.id else setting.defaultProfileId)
        }
        pendingRegistration = null
        // The save ends the add flow: Kestrel forgets its sign-in, so that the next add flow signs in again (design D10).
        if (signInState.value is SignInState.Succeeded) signInState.value = SignInState.Idle
        tabOpened = false
        return profile
    }

    /**
     * Deletes a profile (design D9 and D15): for the ChatGPT plan it first revokes the refresh token, while
     * `auth.openai.com` is still on the allowlist; then it deletes the credential, the model list and the profile. The
     * ChatGPT registration and the host ID stay. Returns false when OpenAI did not confirm the sign-out.
     */
    suspend fun delete(profileId: String): Boolean {
        val profile = settings.get(SettingKeys.PROVIDERS).profiles.firstOrNull { it.id == profileId } ?: return true
        val credential = profile.credentialId?.let { credentials.read(it) }
        val confirmed = if (chatgptPlan(profile) && credential is Credential.OAuthToken) ChatGptPlanFlow.revoke(http, credential) else true
        profile.credentialId?.let { credentials.delete(it) }
        settings.set(SettingKeys.providerModels(profileId), ModelList())
        update { setting ->
            val profiles = setting.profiles.filterNot { it.id == profileId }
            setting.copy(profiles = profiles, defaultProfileId = setting.defaultProfileId.takeIf { it != profileId } ?: profiles.firstOrNull()?.id)
        }
        return confirmed
    }

    suspend fun setDefault(profileId: String) = update { it.copy(defaultProfileId = profileId) }

    suspend fun rename(profileId: String, name: String) = updateProfile(profileId) { it.copy(name = name) }

    suspend fun setModel(profileId: String, role: ModelRole, model: ProfileModel) =
        updateProfile(profileId) { if (role == ModelRole.LIGHTWEIGHT) it.copy(lightweight = model) else it.copy(versatile = model) }

    /** Switches a borrowed sign-in on or off for every profile that uses it (design D9). */
    suspend fun setSwitch(identity: String, on: Boolean) = update { it.copy(signInSwitches = it.signInSwitches + (identity to on)) }

    /** Replaces a profile's credential under its own ID, as "Sign in again" and "Change key" do (design D15). */
    suspend fun replaceCredential(profileId: String, result: SignInResult) {
        val profile = settings.get(SettingKeys.PROVIDERS).profiles.firstOrNull { it.id == profileId } ?: return
        val id = credentials.store(result.credential, profile.credentialId)
        val signedIn = profile.method == AuthMethod.DEVICE_CODE || profile.method == AuthMethod.BROWSER
        updateProfile(profileId) {
            it.copy(credentialId = id, signedInAt = if (signedIn) clock() else it.signedInAt, discoveredHosts = (it.discoveredHosts + result.hosts).distinct())
        }
        pendingRegistration = null
        // Another GitHub account may have another Copilot base, so the next request discovers it again.
        if (profile.providerId == CatalogLoader.COPILOT) copilot.forget(profileId)
    }

    suspend fun models(profileId: String): ModelList = settings.get(SettingKeys.providerModels(profileId))

    /** "Refresh models" on the profile page: the profile's list again, with its hosts leased. */
    suspend fun refreshModels(profileId: String): List<ModelEntry> {
        val profile = settings.get(SettingKeys.PROVIDERS).profiles.firstOrNull { it.id == profileId } ?: return emptyList()
        val discovered = policy.openTemporaryHosts(ProfileHosts.hosts(profile, catalog)).use { discover(profile) }.orEmpty()
        settings.set(SettingKeys.providerModels(profileId), ModelList(discovered, clock()))
        return OfferedModels.rows(catalog, profile.providerId, profile.choiceId, discovered)
    }

    /** "Check allowance" for a Copilot profile (design D12). */
    suspend fun allowance(profileId: String): List<CopilotQuota> {
        val profile = settings.get(SettingKeys.PROVIDERS).profiles.firstOrNull { it.id == profileId } ?: return emptyList()
        val token = profile.credentialId?.let { credentials.read(it) } as? Credential.GitHubToken
            ?: throw ProviderException(ProviderError.SignInExpired(profile.providerId))
        return CopilotAllowance(http, catalog.overrides.copilot.ideHeaders).check(token)
    }

    /** Stores Copilot's new API base and host, and waits up to 5 s until the allowlist holds it (design D12). */
    private suspend fun moveCopilotBase(profile: Profile, base: HttpUrl) {
        updateProfile(profile.id) { it.copy(baseUrl = base.toString(), discoveredHosts = (it.discoveredHosts + base.host).distinct()) }
        withTimeoutOrNull(ALLOWLIST_WAIT_MILLIS) {
            applied.first { setting -> setting?.profiles?.any { it.id == profile.id && it.baseUrl == base.toString() } == true }
        }
    }

    private suspend fun updateProfile(profileId: String, change: (Profile) -> Profile) =
        update { setting -> setting.copy(profiles = setting.profiles.map { if (it.id == profileId) change(it) else it }) }

    private suspend fun update(change: (ProvidersSetting) -> ProvidersSetting) = writes.withLock {
        settings.set(SettingKeys.PROVIDERS, change(settings.get(SettingKeys.PROVIDERS)))
    }

    companion object {
        /** The name of SettingsPage.PROVIDERS, which SignInService puts into MainActivity's EXTRA_PAGE. */
        const val PROVIDERS_PAGE = "PROVIDERS"
        private const val ALLOWLIST_WAIT_MILLIS = 5_000L
    }
}
```

- [ ] **Step 5: Add the provider graph to AppGraph**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    // Plan 4C: provider graph
```

with:

```kotlin
    /**
     * The providers' sign-ins, credentials and clients, which the Providers page and sub-project 5 use (design D9 to D13
     * of sub-project 4). Built on first use, because it reads the catalog.
     */
    // Named in full, because the design gives this plan only the marker's place in AppGraph.kt.
    internal val providers: dev.kestrel.app.providers.ProviderGraph by lazy {
        dev.kestrel.app.providers.ProviderGraph(appContext, scope, settings, { catalog }, providerHttp, credentialVault, appliedProviders, networkPolicy, reportFailure)
    }

    // Plan 4C: provider graph
```

- [ ] **Step 6: Tell the graph when the settings are in front, and add the extra and the permission to MainActivity**

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
import android.net.Uri
import android.os.Bundle
```

with:

```kotlin
import android.net.Uri
import android.os.Build
import android.os.Bundle
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
import androidx.lifecycle.lifecycleScope
import dev.kestrel.app.settings.ModelCheck
```

with:

```kotlin
import androidx.lifecycle.lifecycleScope
import dev.kestrel.app.providers.AndroidLocalNetworkAccess
import dev.kestrel.app.settings.ModelCheck
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
        graph.problems.settingsOpened()
        refresh()
    }

    override fun onPause() {
        graph.timeline.settingsPaused()
        super.onPause()
    }
```

with:

```kotlin
        graph.problems.settingsOpened()
        // SignInService brings the settings back after a sign-in in the browser only while they are not in front (design
        // D10 of sub-project 4).
        graph.providers.settingsResumed = true
        refresh()
    }

    override fun onPause() {
        graph.providers.settingsResumed = false
        graph.timeline.settingsPaused()
        super.onPause()
    }
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
        /** The permissions that an intent may ask for. Plan 3C declares READ_CONTACTS in the manifest. */
        val ASKABLE: Set<String> = setOf(Manifest.permission.READ_CONTACTS)
```

with:

```kotlin
        /**
         * Set by SignInService when it brings the settings back after a sign-in in the browser, so that the Providers page
         * keeps its add flow (design D10 of sub-project 4).
         */
        const val EXTRA_RESUME_ADD = "dev.kestrel.app.extra.RESUME_ADD"

        /**
         * The permissions that an intent may ask for. Plan 3C declares READ_CONTACTS in the manifest. ACCESS_LOCAL_NETWORK
         * is asked for when a task needs a server on the local network after the owner revoked it (design D11 of
         * sub-project 4); Android 13 to 16 do not have it, so it is askable only from Android 17 on.
         */
        val ASKABLE: Set<String> = setOfNotNull(
            Manifest.permission.READ_CONTACTS,
            AndroidLocalNetworkAccess.PERMISSION.takeIf { Build.VERSION.SDK_INT >= AndroidLocalNetworkAccess.LOCAL_NETWORK_API },
        )
```

- [ ] **Step 7: Declare the service, the permissions and the Custom Tabs query, and add the notification's texts**

In `app/src/main/AndroidManifest.xml`, replace:

```xml
    <uses-permission android:name="android.permission.READ_CONTACTS" />
```

with:

```xml
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <!-- Granted at install, for SignInService, which keeps a sign-in running while the browser is in front (design D10 of
         sub-project 4; prototype P5). -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <!-- Android 17 and later, asked for when the owner checks a server on the local network; loopback needs none (design
         D11 of sub-project 4). -->
    <uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
    <!-- Sign-in pages open in a Custom Tab, in Chrome when Chrome offers the Custom Tabs service (design D10 of
         sub-project 4). -->
    <queries>
        <intent>
            <action android:name="android.support.customtabs.action.CustomTabsService" />
        </intent>
    </queries>
```

In `app/src/main/AndroidManifest.xml`, replace:

```xml
        <!-- Keeps Kestrel listening, with the microphone and voice output (design spec, sections 7.2 and 8.5). -->
```

with:

```xml
        <!-- Keeps a sign-in running while the owner signs in in the browser, with a notification and Cancel (design D10 of
             sub-project 4). -->
        <service
            android:name=".providers.SignInService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <!-- Keeps Kestrel listening, with the microphone and voice output (design spec, sections 7.2 and 8.5). -->
```

In `app/src/main/res/values/strings.xml`, replace:

```xml
    <string name="commands_list_detail">Say \"What can I say?\" to come back to this list.</string>
```

with:

```xml
    <string name="commands_list_detail">Say \"What can I say?\" to come back to this list.</string>
    <string name="signin_channel">Sign-in</string>
    <string name="signin_notification">Signing in to %1$s</string>
    <string name="signin_cancel">Cancel</string>
```

- [ ] **Step 8: Build the app and the device tests**

Run: `scripts\gradle.ps1 :app:assembleDebug :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
| `:core:privacy` | The network gateway and its allowlist, the network log, the activity log and encrypted storage | No |
```

with:

```markdown
| `:core:privacy` | The network gateway and its allowlist, the network log, the activity log and encrypted storage, including the credential store and the loopback server for browser sign-in | No |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
| Method | Providers |
|---|---|
| API key, with regional presets where a provider has several endpoints | Most providers |
| Device code: Kestrel shows and speaks a code, and the owner approves it in a browser | GitHub Copilot (github.com and GHE.com tenants), OpenAI Codex, xAI, MiniMax (global and China), Radius |
| Browser sign-in with PKCE: a Custom Tab opens the provider's page, and the redirect comes back to a temporary server on 127.0.0.1 | OpenAI Codex, OpenRouter, Chutes, xAI |
| Pasted token | Anthropic setup-token (`sk-ant-oat01-…`) |
| Imported credentials | AWS keys for Bedrock and Bedrock Mantle; a Google service-account JSON file for Vertex |
| Server address, without an account | LM Studio, Ollama, vLLM, SGLang, llama.cpp, oMLX and LiteLLM |

- Kestrel reuses the public client registrations and redirect addresses that OpenClaw uses, because these providers have no registration for Kestrel. This works today, but a provider can change its policy at any time, so each sign-in method can be switched off on its own. Two examples: Google ended consumer sign-in for the Gemini CLI on 18 June 2026 (B30), and Anthropic accepts setup-token requests because they identify as the Claude Code client (B31).
- Sign-in pages open in the owner's browser, outside Kestrel's process.
- Tokens refresh automatically. When a request fails because a token expired, Kestrel refreshes it once and retries once.
```

with:

```markdown
| Method | Providers |
|---|---|
| API key, with regional presets where a provider has several endpoints; a custom base URL must use HTTPS | Most providers |
| Device code: Kestrel shows a code, and the owner approves it in a browser | GitHub Copilot (github.com and GHE.com tenants), xAI, MiniMax (global and China) |
| Browser sign-in with PKCE: a Custom Tab opens the provider's page, and the redirect comes back to a temporary server on the loopback addresses that the redirect names (spike S6, section 19.1) | The ChatGPT plan, through OpenAI's Sign in with ChatGPT for open-source apps, with a redirect to `127.0.0.1`; OpenRouter, with a redirect to `localhost` |
| Imported credentials | A Bedrock API key, or IAM access keys that Kestrel signs with SigV4, for Bedrock; a Bedrock API key for Bedrock Mantle; a Google service-account JSON file for Vertex |
| Server address, without an account | LM Studio, Ollama, vLLM, SGLang, llama.cpp, oMLX and LiteLLM, over HTTPS, or over plain HTTP on the phone or the owner's local network (section 13.1) |

- Three sign-ins borrow another program's client registration or identity, because these providers have no registration for Kestrel: GitHub Copilot's (GitHub's own client, with VS Code's editor headers), xAI's (the Grok CLI's client and proxy) and MiniMax's (probably OpenClaw's client). The owner chose to build all three, with Copilot's switched on and the other two switched off until he turns them on (option B; section 20, item 2). The Providers page has one switch for each: a switched-off sign-in is not offered, its profiles' requests fail with "the sign-in is switched off", and its hosts leave the network allowlist. Each borrowed sign-in, and the ChatGPT plan's, shows one sentence on what it borrows and the links to its provider's terms. Anthropic's setup-token, which works only when Kestrel poses as Claude Code, is never offered, and neither are the Codex CLI's sign-ins.
- The ChatGPT plan's sign-in is OpenAI's official route for open-source apps, so it borrows nothing: Kestrel registers itself at the first sign-in, keeps the issued client ID, checks the ID token, and revokes the refresh token when the owner deletes the profile. Its requests count against the owner's ChatGPT plan, not against Kestrel's cost cap.
- Sign-in pages open in a Custom Tab in Kestrel's task, in Chrome when Chrome offers Custom Tabs and otherwise in the default browser, so that the owner's own browser handles the password. Every sign-in runs inside the foreground service `SignInService` (type `dataSync`), whose notification has a Cancel action, because Android freezes the process of an app in the background about 70 s after the tab opens (prototype P5). After a browser sign-in, Kestrel brings its settings back to the front.
- Tokens refresh automatically: before they expire, and once after a 401, after which the request is sent once more. A refresh that fails for a moment (no network, a timeout, a rate limit or a server error) keeps the sign-in; only the provider's refusal ends it, and then Kestrel asks the owner to sign in again.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `MODIFY_AUDIO_SETTINGS`
```

with:

```markdown
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC` (for the sign-in service, section 10.4), `MODIFY_AUDIO_SETTINGS`
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
| `ACCESS_LOCAL_NETWORK` (the "Nearby devices" group, Android 17 and later) | Runtime | When the owner adds a server on the local network (B34) |
```

with:

```markdown
| `ACCESS_LOCAL_NETWORK` (the "Nearby devices" group, Android 17 and later) | Runtime | When the owner checks a server on the local network, and when a task needs one after the permission was revoked (B34); loopback needs none (section 19.1) |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
- App visibility uses `<queries>` for launcher apps, for the home app (so that "show previous app" can tell the home app's overview from the app that comes back) and for the deep-link schemes.
```

with:

```markdown
- App visibility uses `<queries>` for launcher apps, for the home app (so that "show previous app" can tell the home app's overview from the app that comes back), for the deep-link schemes, and for the Custom Tabs service, so that sign-in pages open in Chrome when Chrome offers it (section 10.4).
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
These run on `kestrel-api37`, and on the A71 before each release. They use the fixture screens in `:testapp`: classic Views, Compose, a WebView page, unlabeled icon buttons, long lists, dialogs, text fields, a secure window and an injection screen. They cover snapshots, every action and its fallback, the overlays, text editing, the touch watcher, and OCR on fixture screenshots.
```

with:

```markdown
These run on `kestrel-api37`, and on the A71 before each release. They use the fixture screens in `:testapp`: classic Views, Compose, a WebView page, unlabeled icon buttons, long lists, dialogs, text fields, a secure window and an injection screen. They cover snapshots, every action and its fallback, the overlays, text editing, the touch watcher, and OCR on fixture screenshots.

The providers' device tests check the credential store with the device's Keystore, the loopback server of browser sign-in, the Custom Tab with the device's own Chrome (a redirect from a public page to the loopback server runs only with the instrumentation argument `kestrelRedirectCheck=true`), and a profile against a mock server on the device through the real network gateway. They use test-only names and never touch the owner's profiles, credentials or spending.
```

- [ ] **Step 10: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`, with `:core:providers` R₀ + 98, `:app` A₀ and `:core:privacy` P₀, lint without issues in every module (`app\build\intermediates\lint_intermediate_text_report\debug\lintReportDebug\lint-results-debug.txt` says "No issues found."), and the audit: `:app` is an Android module, so its reports are `app\build\reports\kestrel-audit\network-apis-debug.txt` and `network-apis-release.txt`, and neither lists a finding, because `:app` reaches the loopback server only through `LoopbackServer`'s own methods, and the licence check passes unchanged.

- [ ] **Step 11: Run the device tests on the emulator**

Wait for the emulator lock and take it, as "Before you start" says, with the name `plan 4C task 6`. Delete it at the end of this step, also when a command fails: `Remove-Item .superpowers\sdd\emulator.lock`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.providers.ProviderEndToEndTest,dev.kestrel.app.providers.CustomTabSignInTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`. The result file in `app\build\outputs\androidTest-results\connected\debug\` shows 6 tests and 0 failures: `ProviderEndToEndTest` 3 and `CustomTabSignInTest` 3, of which `aRedirectFromAPublicPageReachesTheLoopbackServerWithoutAPrompt` is skipped, because the argument `kestrelRedirectCheck` is missing. While `theTabOpensInKestrelsTaskKestrelStaysInTheForegroundAndTheSettingsComeBack` runs, the emulator shows Kestrel's settings, then Chrome's Custom Tab with the page "Kestrel received the sign-in. You can close this tab.", and then Kestrel's settings again. The test checks that the tab is in Kestrel's task and that `SignInService` runs in the foreground (`dumpsys activity services`); Android holds an instrumented process at the foreground-service level itself, so the freezer and the return to the front of a real sign-in are checked by P5 and by the session's sign-in on the A71 (D20, step 5). If the test stops with "Chrome shows its first-run screens", open Chrome on the emulator once, finish those screens, and run the command again. `aSignInThatEndsBeforeTheServiceSawItWaitStopsTheService` starts the service, then a sign-in with a GHE.com tenant that is not one, which fails at once without a request, and checks that the service leaves the foreground. The other device tests of `:app` are now O₀ + 6.

- [ ] **Step 12: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 13: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/providers/AndroidLocalNetworkAccess.kt app/src/main/kotlin/dev/kestrel/app/providers/LoopbackListenerAdapter.kt app/src/main/kotlin/dev/kestrel/app/providers/CustomTabs.kt app/src/main/kotlin/dev/kestrel/app/providers/SignInService.kt app/src/main/kotlin/dev/kestrel/app/providers/ProviderGraph.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/androidTest/kotlin/dev/kestrel/app/providers/ProviderEndToEndTest.kt app/src/androidTest/kotlin/dev/kestrel/app/providers/CustomTabSignInTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): add the provider graph, the sign-in service and the Custom Tab" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The settings

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/providers/ProviderTexts.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/ProviderData.kt`, `ProvidersPage.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, `SetupChecklist.kt`, `ChecklistPage.kt`, `SettingsData.kt`, `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, `app/src/main/res/values/strings.xml`, `docs/superpowers/specs/2026-11-12-kestrel-design.md` (13.1, 14.2 and 15)
- Test: the JVM test `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`; the device tests `app/src/androidTest/kotlin/dev/kestrel/app/settings/ProvidersPageTest.kt`, `SettingsAppTest.kt` and `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`

**Interfaces:**
- Consumes: Task 6's `ProviderGraph`, `SignInState`, `CheckResult`, `CustomTabs`, `AndroidLocalNetworkAccess`, `MainActivity.EXTRA_RESUME_ADD`; Tasks 1 to 5's `Entries`, `SignInRegistry`, `SignInFailure`, `OfferedModels`, `LocalNetwork`, `CopilotQuota`; plan 4A's `Catalog`, `ChoiceOverride` (`termsLinks`, `borrowedIdentity`, `chatgptPlan`, `planUsage`), `ProvidersSetting.switchedOn`, `PriceTable`, `Spending`, `ProviderError`.
- Produces: `enum class SettingsPage { …, PROVIDERS }`; `class PageRequest(val page: SettingsPage, val resumeAdd: Boolean = false)`; `SetupStatus.providerReady: Boolean = false`; `SettingsData.providerReady: Flow<Boolean>` (default `flowOf(false)`) and `SettingsData.providers: ProviderData?` (default null); `ChecklistPage(states, models, samsung, onAction, onOpenProviders, onBack)`; `interface ProviderData` (package `dev.kestrel.app.settings`; its members are listed in `ProviderData.kt`, among them `reopenPage()` and `suspend fun signInAgain(profileId)`) and `internal class StoredProviderData(graph: ProviderGraph) : ProviderData`; `@Composable internal fun ProvidersPage(data: ProviderData, onBack: () -> Unit)`; `internal object ProviderTexts { const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"; const val FAILED; …; fun check(error: ProviderError, provider: String): String; fun signIn(failure: SignInFailure, provider: String): String; fun spoken(error: ProviderError, provider: String): String }`.

Design D17 and D18, with D9's sign-in states and D11's request for `ACCESS_LOCAL_NETWORK`. The page "Providers and profiles" lists the profiles with the default marked, their models and this month's spending, "Add a profile", and one switch per borrowed sign-in with its sentence and the links to its terms. It keeps its own sub-pages (the add flow with the sign-in, and the profile page) in saved state, so a `PageRequest` that names it again, as the return from a browser sign-in does, keeps the add flow. The add flow chooses a provider from a searchable list and a method; takes the key, the key file (Android's file picker; Kestrel keeps only Google's four fields), the AWS keys or the address, the region or location, and for a provider without a list the model first; asks for `ACCESS_LOCAL_NETWORK` before it checks a server on the owner's network, after one sentence that says why; runs "Check"; and offers the two models and the save. The ChatGPT plan's button says "Continue with ChatGPT", its first save shows OpenAI's dialog, and its profile shows "Using ChatGPT plan", "Manage usage" and the plan's limit while `planLimitReached` holds it. `ProviderTexts` holds the page's and sub-project 5's sentences for each error, in OpenAI's words where D9 quotes them. The checklist step `PROVIDER` is done when the default profile can be used, and its button opens the page through a callback, so that `SetupAction` and `MainActivity.perform` stay unchanged. `ProvidersPageTest` uses a fake data layer and the app's own catalog, so it never adds a profile for a remote provider.

Each method shows a name: the override's label when there is one, else the catalog's label of the choice, else the provider's name, so that Copilot's card reads "GitHub Copilot". The page takes its activity from `LocalActivity` (activity-compose 1.13.0 has it), because lint's `ContextCastToActivity` refuses a cast of `LocalContext`. After a save, a cancel or leaving the add flow, the add flow's sign-in and its credential are forgotten, and the profile page replaces a credential only after a sign-in that it started itself. Typed keys and secrets stay in `remember`, never in the activity's saved state. The page's coroutines turn every failure but a cancellation into a sentence, so that none ends Kestrel. A custom base URL is required, Vertex offers any location in a field below its three buttons, a check that failed after a sign-in can run again, the browser's sign-in page can be opened again after its tab was closed, a ChatGPT plan sign-in that OpenAI refused or that never came back offers "Register with this account" when no ChatGPT plan profile exists, and after two refusals of `ACCESS_LOCAL_NETWORK` the page opens Kestrel's app settings, as `MainActivity` does. A plan's tokens use a plurals resource. "Check" refuses a Vertex location that is not in Google's form and the add flow a GHE.com tenant that is not one, each with a sentence; a key file that Android cannot open counts as a bad key file; and "Register with this account" reuses a client ID that OpenAI issued in the same add flow, so that one add flow never registers Kestrel twice. `ProvidersPageTest` also checks the catalog's label of a choice without its own ("OpenAI API Key") and that a sign-in that the profile page did not start never replaces its credential.

- [ ] **Step 1: Write the failing tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/settings/ProvidersPageTest.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.R
import dev.kestrel.app.providers.CheckResult
import dev.kestrel.app.providers.SignInState
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.client.ModelDefaults
import dev.kestrel.providers.copilot.CopilotQuota
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.SignInFailure
import dev.kestrel.providers.signin.SignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The page "Providers and profiles" with a fake data layer (design D18 and D19), so that it never adds a profile for a
 * remote provider and never touches the owner's profiles: a local server with a key, the masked key, "Change key", the
 * default, the deletion, the switches, the links to the terms, and the ChatGPT plan's notices after a fake sign-in.
 */
class ProvidersPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val data = FakeProviderData()

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun show() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ProvidersPage(data) {} } } }
    }

    private fun type(label: String, value: String) {
        compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextInput(value)
    }

    /** Taps the first control that shows [label], such as a button or a page's entry, but not a title or a text field with the same text. */
    private fun tap(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction() and !hasSetTextAction()).onFirst().performScrollTo().performClick()
    }

    /** Taps a button of a dialog. A dialog does not scroll, so performScrollTo would fail there. */
    private fun tapInDialog(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun shows(label: String): Boolean = compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    private fun addProvider(search: String, provider: String, choice: String) {
        tap(text(R.string.providers_add))
        type(text(R.string.providers_search), search)
        tap(provider)
        tap(choice)
    }

    @Test
    fun aLocalServerWithAKeyIsCheckedSavedAndItsKeyShownMasked() {
        show()
        addProvider("Ollama", "Ollama", "Server address")
        type(text(R.string.providers_server_key), "sk-test-a1b2")
        tap(text(R.string.providers_check))
        compose.waitUntil(5_000) { shows(text(R.string.providers_save)) }

        tap(text(R.string.providers_save))
        compose.waitUntil(5_000) { data.saved.isNotEmpty() }

        val (profile, credential) = data.saved.single()
        assertEquals("http://127.0.0.1:11434/", profile.baseUrl)
        assertEquals(LocalServerKind.OLLAMA, profile.localServer)
        assertEquals(Credential.ApiKey("sk-test-a1b2"), credential)
        assertEquals("llama-test", profile.lightweight.id)
        tap("Ollama (${text(R.string.providers_default)})")
        compose.onNodeWithText(text(R.string.providers_key_masked, "…a1b2")).performScrollTo()
    }

    @Test
    fun aProfileCanChangeItsKeyBecomeTheDefaultAndBeDeleted() {
        data.setting.value = ProvidersSetting(listOf(KEY_PROFILE, OTHER_PROFILE), defaultProfileId = KEY_PROFILE.id)
        show()

        tap("Second")
        // A sign-in that this page did not start, as an add flow's, never replaces the profile's credential (design D10).
        compose.waitForIdle()
        data.signIn.value = SignInState.Succeeded("groq", "groq-api-key", SignInResult(Credential.ApiKey("sk-test-stale1")))
        compose.waitForIdle()
        tap(text(R.string.providers_make_default))
        type(text(R.string.providers_key), "sk-test-new9")
        tap(text(R.string.providers_change_key))
        tap(text(R.string.providers_delete))
        tapInDialog(text(R.string.providers_delete_yes))
        compose.waitUntil(5_000) { data.deleted.isNotEmpty() }

        assertEquals(listOf(OTHER_PROFILE.id), data.defaults)
        assertEquals(listOf(OTHER_PROFILE.id to Credential.ApiKey("sk-test-new9")), data.replaced)
        assertEquals(listOf(OTHER_PROFILE.id), data.deleted)
    }

    @Test
    fun theBorrowedSignInsHaveSwitchesAndTheLinksToTheirTerms() {
        show()

        compose.onNodeWithContentDescription(text(R.string.providers_switch_xai)).performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { data.switches.isNotEmpty() }

        assertEquals(listOf("xai" to true), data.switches)
        assertTrue(shows("Acceptable Use Policies"))
        assertTrue(shows("M Plan FAQ"))
    }

    @Test
    fun theChatGptPlanShowsOpenAisNoticeAfterItsFirstSignInAndItsStates() {
        show()
        tap(text(R.string.providers_add))
        type(text(R.string.providers_search), "OpenAI")
        tap("OpenAI")
        // openai-api-key has no label in overrides.json, so its card shows the catalog's label (design D18).
        assertTrue(shows("OpenAI API Key"))
        // The list of methods says that the ChatGPT plan uses OpenAI's own sign-in (decision 2).
        assertTrue(shows(text(R.string.providers_chatgpt_official)))
        tap("Continue with ChatGPT")
        data.onSignIn = { _ -> data.signIn.value = SignInState.Failed("openai", "chatgpt-plan", SignInFailure.PLAN_USE_NOT_ALLOWED, null) }
        tap("Continue with ChatGPT")
        compose.onNodeWithText("ChatGPT plan use was not allowed.").performScrollTo()

        data.onSignIn = { consent ->
            assertTrue(consent)
            val token = Credential.OAuthToken("test-access", "test-refresh", null, clientId = "oaiapp_test")
            data.signIn.value = SignInState.Succeeded("openai", "chatgpt-plan", SignInResult(token))
        }
        tap(text(R.string.providers_allow_plan))
        compose.waitUntil(5_000) { shows(text(R.string.providers_save)) }
        tap(text(R.string.providers_save))

        compose.waitUntil(5_000) { shows(text(R.string.providers_plan_dialog_title)) }
        assertTrue(shows(text(R.string.providers_manage_usage)))
        tapInDialog(text(R.string.providers_ok))
        // The save ended the add flow, so Kestrel forgot the sign-in and its token (design D10).
        compose.waitUntil(5_000) { data.signIn.value == SignInState.Idle }
        tap("OpenAI (${text(R.string.providers_default)})")
        compose.onNodeWithText(text(R.string.providers_using_plan)).performScrollTo()
    }

    /** The page's data in memory; the catalog is the app's own, read from its assets. */
    private class FakeProviderData : ProviderData {
        override val catalog: Catalog = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph.catalog
        override val setting = MutableStateFlow(ProvidersSetting())
        override val spending = MutableStateFlow(Spending())
        override val signIn = MutableStateFlow<SignInState>(SignInState.Idle)
        override val browserPage = MutableStateFlow<String?>(null)
        override val planLimitReached = MutableStateFlow(emptySet<String>())
        var onSignIn: (consent: Boolean) -> Unit = {}
        val saved = mutableListOf<Pair<Profile, Credential?>>()
        val defaults = mutableListOf<String>()
        val replaced = mutableListOf<Pair<String, Credential>>()
        val deleted = mutableListOf<String>()
        val switches = mutableListOf<Pair<String, Boolean>>()
        private val keys = mutableMapOf<String, Credential.ApiKey>()

        override fun pageOpened() {
            browserPage.value = null
        }

        override fun reopenPage() = Unit

        override suspend fun signInAgain(profileId: String) {
            val profile = setting.value.profiles.first { it.id == profileId }
            startSignIn(profile.providerId, profile.choiceId, null, consent = false, newRegistration = false)
        }

        override fun startSignIn(providerId: String, choiceId: String, tenant: String?, consent: Boolean, newRegistration: Boolean) {
            signIn.value = SignInState.Waiting(providerId, choiceId)
            onSignIn(consent)
        }

        override fun cancelSignIn() {
            signIn.value = SignInState.Idle
        }

        override fun leaveAddFlow() = cancelSignIn()

        override fun localNetworkAllowed(host: String) = true

        override suspend fun check(draft: Profile, credential: Credential?): CheckResult {
            val row = ModelEntry("llama-test", "Llama test")
            return CheckResult(draft, listOf(row), listOf(DiscoveredModel(row.id, row.name)), ModelDefaults(row.id, row.id))
        }

        override suspend fun save(draft: Profile, credential: Credential?, discovered: List<DiscoveredModel>, makeDefault: Boolean): Profile {
            val profile = draft.copy(credentialId = credential?.let { "test-${draft.id}" })
            saved += profile to credential
            (credential as? Credential.ApiKey)?.let { keys[profile.id] = it }
            val current = setting.value
            setting.value = current.copy(profiles = current.profiles + profile, defaultProfileId = current.defaultProfileId ?: profile.id)
            return profile
        }

        override suspend fun delete(profileId: String): Boolean {
            deleted += profileId
            setting.value = setting.value.copy(profiles = setting.value.profiles.filterNot { it.id == profileId })
            return true
        }

        override suspend fun setDefault(profileId: String) {
            defaults += profileId
            setting.value = setting.value.copy(defaultProfileId = profileId)
        }

        override suspend fun rename(profileId: String, name: String) = Unit

        override suspend fun setModel(profileId: String, role: ModelRole, model: ProfileModel) = Unit

        override suspend fun setSwitch(identity: String, on: Boolean) {
            switches += identity to on
        }

        override suspend fun replaceCredential(profileId: String, result: SignInResult) {
            replaced += profileId to result.credential
            (result.credential as? Credential.ApiKey)?.let { keys[profileId] = it }
        }

        override suspend fun maskedKey(profileId: String): String? = keys[profileId]?.let { "…" + it.key.takeLast(4) }

        override suspend fun models(profileId: String): List<ModelEntry> = emptyList()

        override suspend fun refreshModels(profileId: String): List<ModelEntry> = emptyList()

        override suspend fun allowance(profileId: String): List<CopilotQuota> = emptyList()

        override fun priceKnown(profile: Profile, modelId: String): Boolean = true
    }

    private companion object {
        val KEY_PROFILE = Profile(
            "test-first", "First", "groq", "groq-api-key", AuthMethod.API_KEY, "test-first-key",
            lightweight = ProfileModel("a"), versatile = ProfileModel("b"),
        )
        val OTHER_PROFILE = KEY_PROFILE.copy(id = "test-second", name = "Second", credentialId = "test-second-key")
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, replace:

```kotlin
                SetupStep.PROVIDER to StepState.LATER,
```

with:

```kotlin
                SetupStep.PROVIDER to StepState.TO_DO,
```

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, replace:

```kotlin
    @Test
    fun thePermissionStepNeedsBothPermissions() {
```

with:

```kotlin
    @Test
    fun theProviderStepIsDoneWhenTheDefaultProfileCanBeUsed() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.PROVIDER)

        assertEquals(StepState.TO_DO, state(nothingSetUp))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(providerReady = true)))
    }

    @Test
    fun thePermissionStepNeedsBothPermissions() {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
    private class FakeSettingsData : SettingsData {
```

with:

```kotlin
    @Test
    fun theProviderStepOpensTheProvidersPage() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_provider_open)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.providers_title)).assertIsDisplayed()
    }

    private class FakeSettingsData : SettingsData {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`, replace:

```kotlin
    @Test
    fun anIntentWithAPageOpensThatPageInTheRunningApp() {
```

with:

```kotlin
    @Test
    fun theReturnFromASignInKeepsTheAddFlowOpen() {
        await(hasText(text(R.string.settings_checklist)))
        compose.runOnUiThread {
            compose.activity.startActivity(
                Intent(compose.activity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAGE, SettingsPage.PROVIDERS.name)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        await(hasText(text(R.string.providers_add)))
        compose.onNodeWithText(text(R.string.providers_add)).performScrollTo().performClick()
        await(hasText(text(R.string.providers_search)))

        // As SignInService does after a sign-in in the browser (design D10 of sub-project 4).
        compose.runOnUiThread {
            compose.activity.startActivity(
                Intent(compose.activity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAGE, SettingsPage.PROVIDERS.name)
                    .putExtra(MainActivity.EXTRA_RESUME_ADD, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        compose.waitForIdle()

        await(hasText(text(R.string.providers_search)))
    }

    @Test
    fun anIntentWithAPageOpensThatPageInTheRunningApp() {
```

- [ ] **Step 2: Run the JVM test to verify that it fails**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD FAILED`, because the test does not compile: "No parameter with name 'providerReady' found".

- [ ] **Step 3: Write the texts, the data layer and the page**

Create `app/src/main/kotlin/dev/kestrel/app/providers/ProviderTexts.kt`:

```kotlin
package dev.kestrel.app.providers

import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.signin.SignInFailure

/**
 * The sentences about providers that Kestrel says or shows (design D9, D17 and D18). Sub-project 5 speaks [spoken] for a
 * failed call; the Providers page shows [signIn] and [check]. They name the provider by its label and hold no data of
 * the owner's.
 */
internal object ProviderTexts {
    const val PLAN_LIMIT = "Your ChatGPT plan's usage limit is reached."
    const val NOT_ELIGIBLE = "This ChatGPT account has no plan that Kestrel can use (Plus or Pro)."
    const val KEY_CHARACTERS = "The key has a character that keys do not have. Paste it again."
    const val KEY_REFUSED = "The key was refused."
    const val SIGN_OUT_UNCONFIRMED = "OpenAI did not confirm the sign-out. You can disconnect Kestrel in ChatGPT's settings."

    /** A failure that no other sentence names, such as an error of Android's Keystore; its class is logged elsewhere. */
    const val FAILED = "Kestrel could not do this. Try again."

    /** Where the owner manages Kestrel's use of the ChatGPT plan, and disconnects it (OpenAI's "Accounts and sessions"). */
    const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"

    /** The sentence that sub-project 5 speaks when a call to [provider] fails with [error] (spec 14.2). */
    fun spoken(error: ProviderError, provider: String): String = when (error) {
        ProviderError.Unauthorized -> "$provider refused the key. Open Kestrel to change it."
        is ProviderError.SignInExpired -> "Your sign-in to $provider expired. Open Kestrel to sign in again."
        is ProviderError.SignInSwitchedOff -> "The sign-in to $provider is switched off in Kestrel's settings."
        is ProviderError.RateLimited -> "$provider is busy. Try again in a moment."
        is ProviderError.ServerError -> "$provider has a problem. Try again later."
        ProviderError.Timeout -> "$provider did not answer in time."
        ProviderError.Unreachable -> "I can't reach $provider. Check the network."
        ProviderError.NotAllowed -> "Kestrel's settings do not allow $provider's address."
        ProviderError.LocalNetworkNotAllowed -> "Kestrel may not reach servers on the local network. Open Kestrel to allow it."
        is ProviderError.QuotaOrPolicy -> when (error.code) {
            "subscription_sharing_usage_limit_exceeded" -> PLAN_LIMIT
            "subscription_sharing_user_not_eligible" -> NOT_ELIGIBLE
            else -> if (error.status == 429) PLAN_LIMIT else "$provider refused the request: the credits are used up, or a policy blocks the model."
        }
        is ProviderError.BadRequest -> "$provider could not use the request."
        ProviderError.BadCredential -> "The key for $provider has a character that keys do not have. Open Kestrel to change it."
        ProviderError.Malformed -> "$provider gave an answer that Kestrel cannot read."
    }

    /** What the sign-in page says when a sign-in ended without a credential (design D9 and D18). */
    fun signIn(failure: SignInFailure, provider: String): String = when (failure) {
        SignInFailure.CANCELLED -> "You cancelled the sign-in."
        SignInFailure.REFUSED -> "$provider refused the sign-in."
        SignInFailure.EXPIRED -> "The code expired before the sign-in was approved. Start again."
        SignInFailure.NO_CALLBACK -> "The browser did not come back to Kestrel within 5 minutes."
        SignInFailure.UNTRUSTED_ADDRESS -> if (provider == "Copilot") {
            "Copilot returned an address that Kestrel does not trust."
        } else {
            "$provider's sign-in returned an address that Kestrel does not trust."
        }
        SignInFailure.REGISTRATION_INCOMPLETE -> "OpenAI did not finish Kestrel's registration. Try again."
        SignInFailure.REGISTRATION_REFUSED -> "OpenAI did not accept Kestrel's registration."
        SignInFailure.PLAN_USE_NOT_ALLOWED -> "ChatGPT plan use was not allowed."
        SignInFailure.OTHER_ACCOUNT -> "This is another ChatGPT account than the one Kestrel is registered with."
        SignInFailure.EXCHANGE_FAILED -> "The sign-in did not finish."
        SignInFailure.TIMED_OUT -> "Android ended the sign-in, because it ran for too long. Start it again."
    }

    /** What the add flow says when its check failed with [error]. */
    fun check(error: ProviderError, provider: String): String = when {
        error == ProviderError.Unauthorized -> KEY_REFUSED
        error is ProviderError.QuotaOrPolicy && error.status == 403 && error.code != "subscription_sharing_user_not_eligible" -> KEY_REFUSED
        else -> spoken(error, provider)
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/ProviderData.kt`:

```kotlin
package dev.kestrel.app.settings

import dev.kestrel.app.providers.CheckResult
import dev.kestrel.app.providers.ProviderGraph
import dev.kestrel.app.providers.SignInState
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.client.LocalNetwork
import dev.kestrel.providers.client.OfferedModels
import dev.kestrel.providers.copilot.CopilotQuota
import dev.kestrel.providers.cost.PriceTable
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.Entries
import dev.kestrel.providers.signin.SignInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What the Providers page reads and changes (design D18). [StoredProviderData] connects it to the provider graph; the
 * page's device test uses a fake, so that it never adds a profile for a remote provider.
 */
interface ProviderData {
    val catalog: Catalog
    val setting: Flow<ProvidersSetting>
    val spending: Flow<Spending>
    val signIn: StateFlow<SignInState>

    /** A sign-in page that the page opens in a Custom Tab, then clears with [pageOpened]. */
    val browserPage: StateFlow<String?>

    /** The ChatGPT plan profiles whose plan's usage limit a call reached (design D17). */
    val planLimitReached: StateFlow<Set<String>>

    fun pageOpened()

    /** Opens the running browser sign-in's page again, after the owner closed its tab. */
    fun reopenPage()

    fun startSignIn(providerId: String, choiceId: String, tenant: String? = null, consent: Boolean = false, newRegistration: Boolean = false)

    fun cancelSignIn()

    /** "Sign in again" on a profile's page, on the GHE.com tenant of its GitHub token where it has one. */
    suspend fun signInAgain(profileId: String)

    fun leaveAddFlow()

    /** Whether Android lets Kestrel reach [host] now; false only for a host on the owner's network without the permission. */
    fun localNetworkAllowed(host: String): Boolean

    suspend fun check(draft: Profile, credential: Credential?): CheckResult

    suspend fun save(draft: Profile, credential: Credential?, discovered: List<DiscoveredModel>, makeDefault: Boolean): Profile

    /** Deletes the profile; false when OpenAI did not confirm the ChatGPT plan's sign-out. */
    suspend fun delete(profileId: String): Boolean

    suspend fun setDefault(profileId: String)

    suspend fun rename(profileId: String, name: String)

    suspend fun setModel(profileId: String, role: ModelRole, model: ProfileModel)

    suspend fun setSwitch(identity: String, on: Boolean)

    suspend fun replaceCredential(profileId: String, result: SignInResult)

    /** An API key's last four characters ("…a1b2"), or null for another credential. */
    suspend fun maskedKey(profileId: String): String?

    /** The rows that the profile offers, from its stored list and the catalog. */
    suspend fun models(profileId: String): List<ModelEntry>

    suspend fun refreshModels(profileId: String): List<ModelEntry>

    suspend fun allowance(profileId: String): List<CopilotQuota>

    /** Whether Kestrel knows the price of [modelId] on [profile] today (design D14). */
    fun priceKnown(profile: Profile, modelId: String): Boolean
}

internal class StoredProviderData(private val graph: ProviderGraph) : ProviderData {
    override val catalog: Catalog get() = graph.catalog
    override val setting: Flow<ProvidersSetting> = graph.stored.filterNotNull()
    override val spending: Flow<Spending> = graph.spending
    override val signIn: StateFlow<SignInState> = graph.signIn
    override val browserPage: StateFlow<String?> = graph.browserPage
    override val planLimitReached: StateFlow<Set<String>> get() = graph.repository.planLimitReached

    override fun pageOpened() = graph.pageOpened()

    override fun reopenPage() = graph.reopenPage()

    override suspend fun signInAgain(profileId: String) = graph.signInAgain(profileId)

    override fun startSignIn(providerId: String, choiceId: String, tenant: String?, consent: Boolean, newRegistration: Boolean) =
        graph.startSignIn(providerId, choiceId, tenant, consent, newRegistration)

    override fun cancelSignIn() = graph.cancelSignIn()

    override fun leaveAddFlow() = graph.leaveAddFlow()

    override fun localNetworkAllowed(host: String): Boolean = !LocalNetwork.needsPermission(host) || graph.localNetwork.allowed(host)

    override suspend fun check(draft: Profile, credential: Credential?): CheckResult = graph.check(draft, credential)

    override suspend fun save(draft: Profile, credential: Credential?, discovered: List<DiscoveredModel>, makeDefault: Boolean): Profile =
        graph.save(draft, credential, discovered, makeDefault)

    override suspend fun delete(profileId: String): Boolean = graph.delete(profileId)

    override suspend fun setDefault(profileId: String) = graph.setDefault(profileId)

    override suspend fun rename(profileId: String, name: String) = graph.rename(profileId, name)

    override suspend fun setModel(profileId: String, role: ModelRole, model: ProfileModel) = graph.setModel(profileId, role, model)

    override suspend fun setSwitch(identity: String, on: Boolean) = graph.setSwitch(identity, on)

    override suspend fun replaceCredential(profileId: String, result: SignInResult) = graph.replaceCredential(profileId, result)

    override suspend fun maskedKey(profileId: String): String? {
        val profile = graph.stored.value?.profiles?.firstOrNull { it.id == profileId } ?: return null
        return (profile.credentialId?.let { graph.credentials.read(it) } as? Credential.ApiKey)?.let { Entries.masked(it.key) }
    }

    override suspend fun models(profileId: String): List<ModelEntry> {
        val profile = graph.stored.value?.profiles?.firstOrNull { it.id == profileId } ?: return emptyList()
        return OfferedModels.rows(graph.catalog, profile.providerId, profile.choiceId, graph.models(profileId).models)
    }

    override suspend fun refreshModels(profileId: String): List<ModelEntry> = graph.refreshModels(profileId)

    override suspend fun allowance(profileId: String): List<CopilotQuota> = graph.allowance(profileId)

    override fun priceKnown(profile: Profile, modelId: String): Boolean {
        val name = graph.catalog.provider(profile.providerId)?.models?.firstOrNull { it.id == modelId }?.name
        return PriceTable(graph.catalog).priceFor(profile, modelId, name, LocalDate.now(ZoneOffset.UTC)).known
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/ProvidersPage.kt`:

```kotlin
package dev.kestrel.app.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kestrel.app.R
import dev.kestrel.app.providers.AndroidLocalNetworkAccess
import dev.kestrel.app.providers.CheckResult
import dev.kestrel.app.providers.CustomTabs
import dev.kestrel.app.providers.ProviderTexts
import dev.kestrel.app.providers.SignInState
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.ChoiceOverride
import dev.kestrel.providers.catalog.Discovery
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.cost.MonthUse
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.CheckMethod
import dev.kestrel.providers.signin.Entries
import dev.kestrel.providers.signin.SignInFailed
import dev.kestrel.providers.signin.SignInFailure
import dev.kestrel.providers.signin.SignInKind
import dev.kestrel.providers.signin.SignInRegistry
import dev.kestrel.providers.signin.GitHubDeviceFlow
import dev.kestrel.providers.signin.SignInResult
import dev.kestrel.providers.styles.VertexUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The page "Providers and profiles" (design D18): the profiles with the default marked, their models and this month's
 * spending, "Add a profile", and one switch per borrowed sign-in with its terms; and its own sub-pages, the add flow with
 * the sign-in and the profile page. Its sub-page is saved state of its own, so a request that names the page again, as
 * when Kestrel comes back from a sign-in in the browser, keeps it (design D10).
 */
@Composable
internal fun ProvidersPage(data: ProviderData, onBack: () -> Unit) {
    var view by rememberSaveable { mutableStateOf(LIST) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var noBrowser by remember { mutableStateOf(false) }
    // LocalActivity, not a cast of LocalContext, which lint refuses (ContextCastToActivity).
    val activity = LocalActivity.current
    val page by data.browserPage.collectAsState()
    // The browser sign-in's page opens from this activity, so that the Custom Tab is in Kestrel's task.
    LaunchedEffect(page) {
        page?.let { url ->
            noBrowser = activity == null || !CustomTabs.open(activity, url)
            data.pageOpened()
        }
    }
    BackHandler(enabled = view != LIST) {
        if (view == ADD) data.leaveAddFlow()
        view = LIST
    }
    if (noBrowser) Text(stringResource(R.string.providers_no_browser))
    when (view) {
        ADD -> AddProfile(data, onSaved = {
            // The profile holds the credential now, so Kestrel forgets the sign-in's result (design D10).
            data.leaveAddFlow()
            view = LIST
        }) {
            data.leaveAddFlow()
            view = LIST
        }
        PROFILE -> ProfilePage(data, profileId.orEmpty(), onDeleted = { confirmed ->
            if (!confirmed) notice = ProviderTexts.SIGN_OUT_UNCONFIRMED
            view = LIST
        }) { view = LIST }
        else -> ProfileList(
            data,
            notice,
            onAdd = {
                notice = null
                // A new add flow always signs in again.
                data.leaveAddFlow()
                view = ADD
            },
            onOpen = {
                profileId = it
                view = PROFILE
            },
            onBack = onBack,
        )
    }
}

private const val LIST = "list"
private const val ADD = "add"
private const val PROFILE = "profile"
private const val VERTEX = "google-vertex"
private const val MAX_KEY_FILE_BYTES = 64 * 1024
private const val NO_ALLOWANCE = "none"

@Composable
private fun ProfileList(data: ProviderData, notice: String?, onAdd: () -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val setting by data.setting.collectAsState(initial = null)
    val spending by data.spending.collectAsState(initial = Spending())
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var problem by remember { mutableStateOf<String?>(null) }
    PageHeader(stringResource(R.string.providers_title), onBack)
    notice?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { uri.openUri(ProviderTexts.CHATGPT_USAGE_URL) }) { Text(stringResource(R.string.providers_open_chatgpt_settings)) }
    }
    val current = setting ?: return
    if (current.profiles.isEmpty()) Text(stringResource(R.string.providers_none), style = MaterialTheme.typography.bodyMedium)
    for (profile in current.profiles) {
        val provider = data.catalog.provider(profile.providerId)
        val title = if (profile.id == current.defaultProfileId) "${profile.name} (${stringResource(R.string.providers_default)})" else profile.name
        val month = spending.of(profile.id, Spending.monthOf(System.currentTimeMillis()))
        PageEntry(
            title,
            listOf(
                provider?.label ?: profile.providerId,
                stringResource(R.string.providers_models, profile.lightweight.id, profile.versatile.id),
                stringResource(R.string.providers_this_month, spendingText(data, profile, month)),
            ).joinToString("\n"),
        ) { onOpen(profile.id) }
    }
    Button(onClick = onAdd) { Text(stringResource(R.string.providers_add)) }
    Text(stringResource(R.string.providers_switches), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    for ((identity, choice) in borrowedIdentities(data)) {
        val title = switchTitle(identity)
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = current.switchedOn(identity, data.catalog),
                        onCheckedChange = { on -> scope.launch { attempt(title, { problem = it }) { data.setSwitch(identity, on) } } },
                        modifier = Modifier.semantics { contentDescription = title },
                    )
                }
                Text(switchDetail(identity), style = MaterialTheme.typography.bodySmall)
                TermsLinks(choice)
            }
        }
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/**
 * Runs [block], and turns every failure but a cancellation into a sentence for the page (design D17), so that a failure,
 * such as a Keystore error, never ends Kestrel.
 */
private suspend fun attempt(provider: String, onProblem: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ProviderException) {
        onProblem(ProviderTexts.check(e.error, provider))
    } catch (e: SignInFailed) {
        onProblem(ProviderTexts.signIn(e.kind, provider))
    } catch (e: Exception) {
        onProblem(ProviderTexts.FAILED)
    }
}

/** A choice's name: the override's label, else the catalog's label of the choice, else the provider's name (design D18). */
private fun choiceLabel(data: ProviderData, provider: ProviderEntry, choice: ChoiceOverride): String =
    choice.label ?: data.catalog.choice(provider.id, choice.choiceId)?.label ?: provider.label

/** Opens Kestrel's page in Android's app settings, where the owner allows a permission that Android no longer asks for. */
private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

/** The borrowed identities of the catalog, each with one of its choices for the links to its terms (design D21). */
private fun borrowedIdentities(data: ProviderData): List<Pair<String, ChoiceOverride>> =
    data.catalog.providers.flatMap { it.authChoices }.mapNotNull { it.settings }
        .filter { it.offered && it.borrowedIdentity != null }
        .distinctBy { it.borrowedIdentity }
        .map { it.borrowedIdentity!! to it }

@Composable
private fun switchTitle(identity: String): String = stringResource(
    when (identity) {
        "copilot" -> R.string.providers_switch_copilot
        "xai" -> R.string.providers_switch_xai
        else -> R.string.providers_switch_minimax
    },
)

@Composable
private fun switchDetail(identity: String): String = stringResource(
    when (identity) {
        "copilot" -> R.string.providers_switch_copilot_detail
        "xai" -> R.string.providers_switch_xai_detail
        else -> R.string.providers_switch_minimax_detail
    },
)

/** The links to a borrowed or plan sign-in's terms, from `overrides.json` (decision 3). */
@Composable
private fun TermsLinks(choice: ChoiceOverride?) {
    val uri = LocalUriHandler.current
    for (link in choice?.termsLinks.orEmpty()) {
        TextButton(onClick = { uri.openUri(link.url) }) { Text(link.label) }
    }
}

/** A month's use: tokens for a plan sign-in, AI Credits for Copilot, and dollars with the credits beside for the others. */
@Composable
private fun spendingText(data: ProviderData, profile: Profile, use: MonthUse): String {
    val choice = data.catalog.choice(profile.providerId, profile.choiceId)?.settings
    return when {
        choice?.planUsage == true -> pluralStringResource(R.plurals.providers_cost_tokens, use.tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), use.tokens)
        profile.providerId == CatalogLoader.COPILOT -> stringResource(R.string.providers_cost_credits, decimal(use.credits))
        else -> stringResource(R.string.providers_cost_usd, decimal(use.credits / 100), decimal(use.credits))
    }
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

/**
 * The add flow (design D9 and D18): choose a provider from a searchable list, choose a sign-in method, enter the key,
 * import the file, type the address or follow the sign-in, choose a region or address where it applies, for a provider
 * without a model list type the model first, "Check", choose the two models, and save.
 */
@Composable
private fun AddProfile(data: ProviderData, onSaved: () -> Unit, onBack: () -> Unit) {
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    var choiceId by rememberSaveable { mutableStateOf<String?>(null) }
    val provider = providerId?.let { data.catalog.provider(it) }
    val choice = provider?.let { p -> choiceId?.let { data.catalog.choice(p.id, it)?.settings } }
    PageHeader(stringResource(R.string.providers_add), onBack)
    when {
        provider == null -> ChooseProvider(data) { providerId = it }
        choice == null -> ChooseMethod(data, provider) { choiceId = it }
        else -> EnterAndCheck(data, provider, choice, onUseKey = { choiceId = it }, onSaved = onSaved)
    }
}

@Composable
private fun ChooseProvider(data: ProviderData, onChoose: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val setting by data.setting.collectAsState(initial = null)
    OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.providers_search)) }, modifier = Modifier.fillMaxWidth())
    val current = setting ?: ProvidersSetting()
    val wanted = search.trim()
    val offered = data.catalog.providers
        .filter { p -> p.authChoices.any { offeredChoice(data, current, it.settings) } }
        .filter { wanted.isEmpty() || it.label.contains(wanted, ignoreCase = true) || it.id.contains(wanted, ignoreCase = true) }
        .sortedBy { it.label.lowercase() }
    for (provider in offered) PageEntry(provider.label, provider.id) { onChoose(provider.id) }
}

/** Whether the add flow offers [choice]: Kestrel implements it, and its borrowed sign-in, if any, is switched on (design D9). */
private fun offeredChoice(data: ProviderData, setting: ProvidersSetting, choice: ChoiceOverride?): Boolean =
    choice != null && choice.offered && (choice.borrowedIdentity?.let { setting.switchedOn(it, data.catalog) } ?: true)

@Composable
private fun ChooseMethod(data: ProviderData, provider: ProviderEntry, onChoose: (String) -> Unit) {
    val setting by data.setting.collectAsState(initial = null)
    Text(stringResource(R.string.providers_choose_method, provider.label), style = MaterialTheme.typography.titleMedium)
    for (choice in provider.authChoices.mapNotNull { it.settings }.filter { offeredChoice(data, setting ?: ProvidersSetting(), it) }) {
        Card(onClick = { onChoose(choice.choiceId) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(choiceLabel(data, provider, choice), style = MaterialTheme.typography.titleSmall)
                if (choice.chatgptPlan) Text(stringResource(R.string.providers_chatgpt_official), style = MaterialTheme.typography.bodySmall)
                choice.borrowedIdentity?.let { Text(switchDetail(it), style = MaterialTheme.typography.bodySmall) }
                TermsLinks(choice)
            }
        }
    }
}

/** The entry, the sign-in, the check, the choice of the two models and the save of one choice. */
@Composable
private fun EnterAndCheck(data: ProviderData, provider: ProviderEntry, choice: ChoiceOverride, onUseKey: (String) -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val local = data.catalog.localServers.firstOrNull { it.id == provider.id }
    val method = Entries.checkMethod(provider, choice)
    // Keys and secrets stay in memory only, never in the activity's saved state (design D10).
    var key by remember { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf(local?.defaultUrl.orEmpty()) }
    var region by rememberSaveable { mutableStateOf(provider.regions.firstOrNull()?.id ?: if (provider.id == VERTEX) "global" else "") }
    var model by rememberSaveable { mutableStateOf(if (provider.discovery == Discovery.STATIC) provider.defaultModel.orEmpty() else "") }
    var accessKeyId by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var session by remember { mutableStateOf("") }
    var tenant by rememberSaveable { mutableStateOf("") }
    var keyFile by remember { mutableStateOf<Credential.ServiceAccountKey?>(null) }
    var signedIn by remember { mutableStateOf<SignInResult?>(null) }
    var result by remember { mutableStateOf<CheckResult?>(null) }
    var credential by remember { mutableStateOf<Credential?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askingLocal by remember { mutableStateOf(false) }
    var withoutRationale by remember { mutableStateOf(false) }
    val draftId = rememberSaveable { "profile-${UUID.randomUUID()}" }
    val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askingLocal = false
        // After two refusals Android shows no dialog, so the owner allows it in the app's settings, as MainActivity does.
        if (!granted && withoutRationale && activity?.shouldShowRequestPermissionRationale(AndroidLocalNetworkAccess.PERMISSION) == false) {
            openAppSettings(context)
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                // A document that Android cannot open, as one in a cloud folder without network, is not a key file.
                val text = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_KEY_FILE_BYTES).decodeToString() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                keyFile = text?.let(Entries::serviceAccount)
                problem = if (keyFile == null) context.getString(R.string.providers_key_file_bad) else null
            }
        }
    }

    fun draft(): Profile {
        val chosen = ProfileModel(model.trim())
        return Profile(
            id = draftId,
            name = provider.label,
            providerId = provider.id,
            choiceId = choice.choiceId,
            method = choice.method,
            credentialId = null,
            baseUrl = when {
                choice.method == AuthMethod.SERVER_ADDRESS -> Entries.serverAddress(address)?.toString()
                choice.customBaseUrl -> Entries.customBaseUrl(baseUrl)?.toString()
                else -> signedIn?.baseUrl
            },
            region = region.takeIf { it.isNotBlank() },
            localServer = local?.kind,
            lightweight = chosen,
            versatile = chosen,
            discoveredHosts = signedIn?.hosts.orEmpty().toList(),
        )
    }

    /** The entered credential, or null after it set [problem]; a server's key is optional. */
    fun entered(): Result<Credential?> = when (choice.method) {
        AuthMethod.API_KEY -> Entries.apiKey(key)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(ProviderTexts.KEY_CHARACTERS))
        AuthMethod.SERVER_ADDRESS -> if (key.isBlank()) {
            Result.success(null)
        } else {
            Entries.apiKey(key)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(ProviderTexts.KEY_CHARACTERS))
        }
        AuthMethod.IMPORTED -> if (provider.id == VERTEX) {
            keyFile?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(context.getString(R.string.providers_key_file_bad)))
        } else {
            Entries.awsKeys(accessKeyId, secret, session)?.let { Result.success(it) }
                ?: Result.failure(IllegalArgumentException(ProviderTexts.KEY_CHARACTERS))
        }
        AuthMethod.DEVICE_CODE, AuthMethod.BROWSER -> signedIn?.credential?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException(context.getString(R.string.providers_waiting)))
    }

    fun check() {
        problem = null
        val typed = entered().getOrElse {
            problem = it.message
            return
        }
        val profile = draft()
        if (choice.method == AuthMethod.SERVER_ADDRESS && profile.baseUrl == null) {
            problem = context.getString(R.string.providers_server_bad)
            return
        }
        if (choice.customBaseUrl && baseUrl.isBlank()) {
            problem = context.getString(R.string.providers_base_url_missing)
            return
        }
        if (choice.customBaseUrl && profile.baseUrl == null) {
            problem = context.getString(R.string.providers_base_url_bad)
            return
        }
        // The location becomes part of the host (plan 4B), and Vertex's check exchanges only the key.
        if (provider.id == VERTEX && !VertexUrls.validLocation(region)) {
            problem = context.getString(R.string.providers_location_bad)
            return
        }
        val host = profile.baseUrl?.let { Entries.serverAddress(it)?.host }
        if (host != null && !data.localNetworkAllowed(host)) {
            // The sentence first, then Android's question when the owner taps "Allow" (design D11).
            askingLocal = true
            return
        }
        askingLocal = false
        busy = true
        scope.launch {
            try {
                attempt(provider.label, { problem = it }) {
                    result = data.check(profile, typed)
                    credential = typed
                }
            } finally {
                busy = false
            }
        }
    }

    // A sign-in that ended: its credential goes on to the check.
    val state by data.signIn.collectAsState()
    LaunchedEffect(state) {
        val done = state as? SignInState.Succeeded ?: return@LaunchedEffect
        if (done.providerId == provider.id && done.choiceId == choice.choiceId && signedIn == null) {
            signedIn = done.result
            check()
        }
    }

    val checked = result
    if (checked != null) {
        ChooseModels(data, provider, choice, checked, credential, onSaved)
        return
    }
    Text(choiceLabel(data, provider, choice), style = MaterialTheme.typography.titleMedium)
    TermsLinks(choice)
    when (choice.method) {
        AuthMethod.API_KEY -> {
            SecretField(key, { key = it }, stringResource(R.string.providers_key))
            if (choice.customBaseUrl) TextField(baseUrl, { baseUrl = it }, stringResource(R.string.providers_base_url))
        }
        AuthMethod.SERVER_ADDRESS -> {
            TextField(address, { address = it }, stringResource(R.string.providers_server_address))
            Text(stringResource(R.string.providers_server_detail), style = MaterialTheme.typography.bodySmall)
            if (Entries.serverAddress(address)?.let(Entries::unencrypted) == true) Text(stringResource(R.string.providers_unencrypted))
            SecretField(key, { key = it }, stringResource(R.string.providers_server_key))
        }
        AuthMethod.IMPORTED -> if (provider.id == VERTEX) {
            Button(onClick = { pickFile.launch(arrayOf("application/json")) }) { Text(stringResource(R.string.providers_key_file)) }
            if (keyFile != null) Text(stringResource(R.string.providers_key_file_ok))
        } else {
            TextField(accessKeyId, { accessKeyId = it }, stringResource(R.string.providers_aws_key_id))
            SecretField(secret, { secret = it }, stringResource(R.string.providers_aws_secret))
            SecretField(session, { session = it }, stringResource(R.string.providers_aws_session))
        }
        AuthMethod.DEVICE_CODE, AuthMethod.BROWSER -> SignInSection(data, provider, choice, tenant, { tenant = it }, onUseKey, busy)
    }
    if (provider.regions.isNotEmpty()) {
        Text(stringResource(R.string.providers_region), style = MaterialTheme.typography.titleSmall)
        for (option in provider.regions) {
            TextButton(onClick = { region = option.id }) { Text(if (option.id == region) "✓ ${option.label}" else option.label) }
        }
    }
    // Vertex offers global, us and eu as buttons, and any other location, such as us-east5, in the field (design D9).
    if (provider.id == VERTEX) {
        TextField(region, { region = it.trim().lowercase() }, stringResource(R.string.providers_location))
    }
    if (method == CheckMethod.SHORT_REQUEST) {
        TextField(model, { model = it }, stringResource(R.string.providers_model_first))
        Text(stringResource(R.string.providers_model_first_detail), style = MaterialTheme.typography.bodySmall)
    }
    if (askingLocal) {
        Text(stringResource(R.string.providers_local_why), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = {
            withoutRationale = activity?.shouldShowRequestPermissionRationale(AndroidLocalNetworkAccess.PERMISSION) == false
            askLocal.launch(AndroidLocalNetworkAccess.PERMISSION)
        }) { Text(stringResource(R.string.providers_local_allow)) }
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    val signIn = choice.method == AuthMethod.DEVICE_CODE || choice.method == AuthMethod.BROWSER
    // After a sign-in whose check failed, the owner can check it again without a new sign-in.
    if (!signIn || (signedIn != null && problem != null && !busy)) {
        Button(onClick = { check() }, enabled = !busy) {
            Text(stringResource(if (busy) R.string.providers_checking else R.string.providers_check))
        }
    }
}

/** The sign-in sub-page: the device code with its address and time, or the browser's sign-in, and their states (design D9 and D18). */
@Composable
private fun SignInSection(
    data: ProviderData,
    provider: ProviderEntry,
    choice: ChoiceOverride,
    tenant: String,
    onTenant: (String) -> Unit,
    onUseKey: (String) -> Unit,
    checking: Boolean = true,
    retry: (() -> Unit)? = null,
) {
    val state by data.signIn.collectAsState()
    val setting by data.setting.collectAsState(initial = null)
    val context = LocalContext.current
    val activity = LocalActivity.current
    val running = state.takeIf { it.matches(provider.id, choice.choiceId) }
    val kind = SignInRegistry.kind(provider.id, choice.choiceId)
    var tenantProblem by remember { mutableStateOf(false) }
    // A profile's "Sign in again" takes the tenant from its token, so only the add flow asks for it.
    if (kind == SignInKind.GITHUB_TENANT && running !is SignInState.Waiting && retry == null) {
        TextField(tenant, onTenant, stringResource(R.string.providers_tenant))
        if (tenantProblem) Text(stringResource(R.string.providers_tenant_bad), style = MaterialTheme.typography.bodyMedium)
    }
    // The add flow's sign-in, which refuses a GHE.com tenant that is not one before anything starts.
    val start = {
        tenantProblem = kind == SignInKind.GITHUB_TENANT && GitHubDeviceFlow.tenant(tenant) == null
        if (!tenantProblem) data.startSignIn(provider.id, choice.choiceId, tenant.takeIf { it.isNotBlank() })
    }
    when (running) {
        is SignInState.Waiting -> {
            val prompt = running.prompt
            if (prompt != null) {
                Text(stringResource(R.string.providers_code_title), style = MaterialTheme.typography.titleSmall)
                Text(prompt.userCode, fontSize = 32.sp)
                Button(onClick = { copySensitive(context, prompt.userCode) }) { Text(stringResource(R.string.providers_copy)) }
                Button(onClick = { if (activity?.let { CustomTabs.open(it, prompt.verificationUrl) } == true) data.pageOpened() }) {
                    Text(stringResource(R.string.providers_open_page))
                }
                Text(prompt.verificationUrl, style = MaterialTheme.typography.bodySmall)
                Countdown(prompt.expiresAtMillis)
            } else {
                Text(stringResource(R.string.providers_waiting))
                // The browser's page opens once by itself; after the owner closed the tab, it opens again from here.
                if (choice.method == AuthMethod.BROWSER) {
                    Button(onClick = { data.reopenPage() }) { Text(stringResource(R.string.providers_open_sign_in_page)) }
                }
            }
            TextButton(onClick = { data.cancelSignIn() }) { Text(stringResource(R.string.providers_cancel)) }
        }
        is SignInState.Failed -> {
            val failure = running.failure
            Text(failure?.let { ProviderTexts.signIn(it, provider.label) } ?: running.error?.let { ProviderTexts.check(it, provider.label) }.orEmpty())
            val ownProfile = setting?.profiles.orEmpty().any { it.providerId == provider.id && it.choiceId == choice.choiceId }
            // A kept registration that OpenAI no longer knows refuses every sign-in, so the owner can register again.
            val register = !ownProfile && (
                failure == SignInFailure.OTHER_ACCOUNT ||
                    (choice.chatgptPlan && (failure == SignInFailure.REGISTRATION_REFUSED || failure == SignInFailure.NO_CALLBACK))
                )
            if (failure == SignInFailure.PLAN_USE_NOT_ALLOWED) {
                Button(onClick = { data.startSignIn(provider.id, choice.choiceId, consent = true) }) { Text(stringResource(R.string.providers_allow_plan)) }
            }
            if (register) {
                Button(onClick = { data.startSignIn(provider.id, choice.choiceId, newRegistration = true) }) {
                    Text(stringResource(R.string.providers_register_account))
                }
            }
            val again = failure != SignInFailure.PLAN_USE_NOT_ALLOWED && failure != SignInFailure.REGISTRATION_REFUSED &&
                !(failure == SignInFailure.OTHER_ACCOUNT && register)
            if (again) {
                Button(onClick = { retry?.invoke() ?: start() }) {
                    Text(stringResource(R.string.providers_try_again))
                }
            }
            apiKeyChoice(provider)?.let { keyChoice ->
                TextButton(onClick = {
                    data.cancelSignIn()
                    onUseKey(keyChoice)
                }) { Text(stringResource(R.string.providers_use_key)) }
            }
        }
        is SignInState.Succeeded -> if (checking) Text(stringResource(R.string.providers_checking))
        else -> Button(onClick = { retry?.invoke() ?: start() }) {
            Text(if (choice.chatgptPlan) choice.label ?: stringResource(R.string.providers_sign_in) else stringResource(R.string.providers_sign_in))
        }
    }
}

private fun SignInState.matches(providerId: String, choiceId: String): Boolean = when (this) {
    is SignInState.Waiting -> this.providerId == providerId && this.choiceId == choiceId
    is SignInState.Succeeded -> this.providerId == providerId && this.choiceId == choiceId
    is SignInState.Failed -> this.providerId == providerId && this.choiceId == choiceId
    SignInState.Idle -> false
}

/** The provider's API-key choice, which a failed or refused sign-in offers instead (design D9 and D10). */
private fun apiKeyChoice(provider: ProviderEntry): String? =
    provider.authChoices.mapNotNull { it.settings }.firstOrNull { it.offered && it.method == AuthMethod.API_KEY }?.choiceId

/** Copies the device code, marked sensitive, so that Android's clipboard preview does not show it (design D9). */
private fun copySensitive(context: Context, code: String) {
    val clip = ClipData.newPlainText("code", code)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

@Composable
private fun Countdown(expiresAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (now < expiresAt) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val left = ((expiresAt - now).coerceAtLeast(0) / 1000).toInt()
    Text(stringResource(R.string.providers_remaining, left / 60, left % 60))
}

/** The two models, the name and the save; the ChatGPT plan's first save shows OpenAI's notice (design D18). */
@Composable
private fun ChooseModels(data: ProviderData, provider: ProviderEntry, choice: ChoiceOverride, checked: CheckResult, credential: Credential?, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val setting by data.setting.collectAsState(initial = null)
    var light by remember { mutableStateOf(checked.defaults.lightweight ?: checked.draft.lightweight.id) }
    var versatile by remember { mutableStateOf(checked.defaults.versatile ?: checked.draft.versatile.id) }
    var name by rememberSaveable { mutableStateOf(provider.label) }
    var makeDefault by rememberSaveable { mutableStateOf(false) }
    var planNotice by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val rows = checked.rows.ifEmpty { listOf(ModelEntry(checked.draft.lightweight.id, checked.draft.lightweight.id)) }
    TextField(name, { name = it }, stringResource(R.string.providers_name))
    ModelChooser(stringResource(R.string.providers_lightweight), rows, light) { light = it }
    ModelChooser(stringResource(R.string.providers_versatile), rows, versatile) { versatile = it }
    SwitchRow(stringResource(R.string.providers_make_default), makeDefault) { makeDefault = it }
    val firstPlan = choice.chatgptPlan &&
        setting?.profiles.orEmpty().none { data.catalog.choice(it.providerId, it.choiceId)?.settings?.chatgptPlan == true }
    Button(onClick = {
        scope.launch {
            attempt(provider.label, { problem = it }) {
                val draft = checked.draft.copy(name = name.ifBlank { provider.label }, lightweight = ProfileModel(light), versatile = ProfileModel(versatile))
                data.save(draft, credential, checked.discovered, makeDefault)
                if (firstPlan) planNotice = true else onSaved()
            }
        }
    }) { Text(stringResource(R.string.providers_save)) }
    problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (planNotice) PlanNotice(onSaved)
}

@Composable
private fun ModelChooser(title: String, rows: List<ModelEntry>, selected: String, onChoose: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    val json = stringResource(R.string.providers_json_actions)
    for (row in rows) {
        val label = if (row.toolMode == ToolMode.JSON) "${row.name} ($json)" else row.name
        TextButton(onClick = { onChoose(row.id) }) { Text(if (row.id == selected) "✓ $label" else label) }
    }
}

/** OpenAI's notice after the first sign-in with the ChatGPT plan (design D18; research r46). */
@Composable
private fun PlanNotice(onDone: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.providers_plan_dialog_title)) },
        text = { Text(stringResource(R.string.providers_plan_dialog_text)) },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.providers_ok)) } },
        dismissButton = { TextButton(onClick = { uri.openUri(ProviderTexts.CHATGPT_USAGE_URL) }) { Text(stringResource(R.string.providers_manage_usage)) } },
    )
}

/**
 * The profile page (design D18): rename, make default, the models of both roles with "Use JSON actions" and a price for a
 * model whose price Kestrel does not know, a local server's switches, "Refresh models", the credential's masked form or
 * "Signed in on <date>", "Sign in again" or "Change key", the unencrypted-connection warning, this month's and last
 * month's cost or tokens, the ChatGPT plan's notes, "Check allowance" for Copilot, and "Delete profile".
 */
@Composable
private fun ProfilePage(data: ProviderData, profileId: String, onDeleted: (Boolean) -> Unit, onBack: () -> Unit) {
    val setting by data.setting.collectAsState(initial = null)
    val spending by data.spending.collectAsState(initial = Spending())
    val limited by data.planLimitReached.collectAsState()
    val signIn by data.signIn.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var rows by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }
    var masked by remember { mutableStateOf<String?>(null) }
    var quotas by remember { mutableStateOf<String?>(null) }
    var newKey by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Only a sign-in that this page started replaces the profile's credential, never an add flow's result.
    var signingIn by rememberSaveable(profileId) { mutableStateOf(false) }
    val profile = setting?.profiles?.firstOrNull { it.id == profileId }
    LaunchedEffect(profileId) {
        attempt(profileId, { problem = it }) {
            rows = data.models(profileId)
            masked = data.maskedKey(profileId)
        }
    }
    LaunchedEffect(signIn) {
        val done = signIn as? SignInState.Succeeded ?: return@LaunchedEffect
        if (signingIn && profile != null && done.providerId == profile.providerId && done.choiceId == profile.choiceId) {
            signingIn = false
            attempt(profile.name, { problem = it }) { data.replaceCredential(profile.id, done.result) }
            data.cancelSignIn()
        }
    }
    PageHeader(profile?.name ?: stringResource(R.string.providers_title), onBack)
    if (profile == null) return
    val choice = data.catalog.choice(profile.providerId, profile.choiceId)?.settings
    val provider = data.catalog.provider(profile.providerId)
    val label = provider?.label ?: profile.providerId
    var name by rememberSaveable(profileId) { mutableStateOf(profile.name) }
    TextField(name, { name = it }, stringResource(R.string.providers_name))
    TextButton(onClick = { scope.launch { attempt(label, { problem = it }) { data.rename(profile.id, name) } } }) { Text(stringResource(R.string.providers_rename)) }
    if (setting?.defaultProfileId != profile.id) {
        Button(onClick = { scope.launch { attempt(label, { problem = it }) { data.setDefault(profile.id) } } }) {
            Text(stringResource(R.string.providers_make_default))
        }
    }
    if (choice?.chatgptPlan == true) {
        Text(stringResource(R.string.providers_using_plan), style = MaterialTheme.typography.titleSmall)
        if (profile.id in limited) Text(stringResource(R.string.providers_plan_limit))
        TextButton(onClick = { uri.openUri(ProviderTexts.CHATGPT_USAGE_URL) }) { Text(stringResource(R.string.providers_manage_usage)) }
    }
    if (choice?.planUsage == true) {
        // Design D14: "Your <plan> pays for these requests, so Kestrel's cost cap does not cover them."
        val cap = if (choice.chatgptPlan) stringResource(R.string.providers_plan_cap_chatgpt) else stringResource(R.string.providers_plan_cap, label)
        Text(cap, style = MaterialTheme.typography.bodySmall)
    }
    TermsLinks(choice?.takeIf { it.chatgptPlan || it.borrowedIdentity != null })
    for (role in ModelRole.entries) {
        ProfileModelSection(data, profile, role, rows) { model -> scope.launch { attempt(label, { problem = it }) { data.setModel(profile.id, role, model) } } }
    }
    Button(onClick = {
        scope.launch { attempt(label, { problem = it }) { rows = data.refreshModels(profile.id) } }
    }) { Text(stringResource(R.string.providers_refresh)) }
    if (profile.baseUrl?.startsWith("http://") == true) Text(stringResource(R.string.providers_unencrypted))
    masked?.let { Text(stringResource(R.string.providers_key_masked, it)) }
    profile.signedInAt?.let { Text(stringResource(R.string.providers_signed_in, DateFormat.getDateInstance().format(Date(it)))) }
    when (profile.method) {
        AuthMethod.DEVICE_CODE, AuthMethod.BROWSER -> {
            // A GHE.com tenant's profile signs in again on the tenant of its token.
            val again = {
                signingIn = true
                scope.launch { attempt(label, { problem = it }) { data.signInAgain(profile.id) } }
                Unit
            }
            Button(onClick = again) { Text(stringResource(R.string.providers_sign_in_again)) }
            if (signingIn && provider != null && choice != null && signIn.matches(profile.providerId, profile.choiceId) && signIn !is SignInState.Succeeded) {
                SignInSection(data, provider, choice, "", {}, {}, retry = again)
            }
        }
        AuthMethod.API_KEY -> {
            SecretField(newKey, { newKey = it }, stringResource(R.string.providers_key))
            Button(onClick = {
                val key = Entries.apiKey(newKey)
                if (key == null) {
                    problem = ProviderTexts.KEY_CHARACTERS
                } else {
                    scope.launch {
                        attempt(label, { problem = it }) {
                            data.replaceCredential(profile.id, SignInResult(key))
                            masked = data.maskedKey(profile.id)
                            newKey = ""
                        }
                    }
                }
            }) { Text(stringResource(R.string.providers_change_key)) }
        }
        AuthMethod.IMPORTED, AuthMethod.SERVER_ADDRESS -> Unit
    }
    val month = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC))
    Text(stringResource(R.string.providers_this_month, spendingText(data, profile, spending.of(profile.id, month.toString()))))
    Text(stringResource(R.string.providers_last_month, spendingText(data, profile, spending.of(profile.id, month.minusMonths(1).toString()))))
    if (profile.providerId == CatalogLoader.COPILOT) {
        val premium = stringResource(R.string.providers_allowance_premium)
        val chat = stringResource(R.string.providers_allowance_chat)
        Button(onClick = {
            scope.launch {
                attempt(label, { quotas = it }) {
                    quotas = data.allowance(profile.id).joinToString("\n") { quota ->
                        String.format(Locale.ROOT, if (quota.label == "premium") premium else chat, String.format(Locale.ROOT, "%.0f", quota.percentRemaining))
                    }.ifEmpty { NO_ALLOWANCE }
                }
            }
        }) { Text(stringResource(R.string.providers_allowance)) }
        quotas?.let { Text(if (it == NO_ALLOWANCE) stringResource(R.string.providers_allowance_none) else it) }
    }
    problem?.let { Text(it) }
    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.providers_delete)) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.providers_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { attempt(label, { problem = it }) { onDeleted(data.delete(profile.id)) } }
                }) { Text(stringResource(R.string.providers_delete_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.providers_cancel)) } },
        )
    }
}

/** One role's model on the profile page, with its switches and a price that the owner may type (design D7, D11 and D14). */
@Composable
private fun ProfileModelSection(data: ProviderData, profile: Profile, role: ModelRole, rows: List<ModelEntry>, onChange: (ProfileModel) -> Unit) {
    val current = profile.model(role)
    val title = stringResource(if (role == ModelRole.LIGHTWEIGHT) R.string.providers_lightweight else R.string.providers_versatile)
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    val listed = rows.isEmpty() || rows.any { it.id == current.id }
    Text(if (listed) current.id else "${current.id} (${stringResource(R.string.providers_not_offered)})")
    for (row in rows.filter { it.id != current.id }) {
        TextButton(onClick = { onChange(current.copy(id = row.id, price = null)) }) { Text(row.name) }
    }
    SwitchRow(stringResource(R.string.providers_json_actions), current.jsonActions) { onChange(current.copy(jsonActions = it)) }
    if (profile.localServer != null) {
        SwitchRow(stringResource(R.string.providers_images), current.images == true) { onChange(current.copy(images = it)) }
        SwitchRow(stringResource(R.string.providers_tools), current.tools != false) { onChange(current.copy(tools = it)) }
    }
    if (current.price != null || !data.priceKnown(profile, current.id)) {
        if (current.price == null) Text(stringResource(R.string.providers_unknown_price), style = MaterialTheme.typography.bodySmall)
        var input by rememberSaveable(current.id, role) { mutableStateOf(current.price?.input?.toString().orEmpty()) }
        var output by rememberSaveable(current.id, role) { mutableStateOf(current.price?.output?.toString().orEmpty()) }
        TextField(input, { input = it }, stringResource(R.string.providers_price_input), KeyboardType.Decimal)
        TextField(output, { output = it }, stringResource(R.string.providers_price_output), KeyboardType.Decimal)
        TextButton(onClick = {
            val inputPrice = input.trim().toDoubleOrNull()
            val outputPrice = output.trim().toDoubleOrNull()
            if (inputPrice != null && outputPrice != null && inputPrice >= 0 && outputPrice >= 0) onChange(current.copy(price = Price(inputPrice, outputPrice)))
        }) { Text(stringResource(R.string.providers_save_price)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun TextField(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A field for a key or a secret, which the screen never shows in plain text. */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
```

- [ ] **Step 4: Add the page to the settings app**

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, VOICE, COMMANDS, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }
```

with:

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, VOICE, COMMANDS, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT, PROVIDERS }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
 * page after the owner has left it.
 */
class PageRequest(val page: SettingsPage)
```

with:

```kotlin
 * page after the owner has left it. [resumeAdd] says that the request is the return from a sign-in in the browser (design
 * D10 of sub-project 4); the Providers page keeps its add flow by itself, because its sub-page is its own saved state.
 */
class PageRequest(val page: SettingsPage, val resumeAdd: Boolean = false)
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
    val states = SetupChecklist.states(status)
```

with:

```kotlin
    // The checklist's provider step reads the stored profiles, which SetupStatusReader does not know.
    val providerReady by data.providerReady.collectAsState(initial = false)
    val states = SetupChecklist.states(status.copy(providerReady = providerReady))
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
            SettingsPage.CHECKLIST -> ScrollingPage { ChecklistPage(states, status.models, status.samsung, onAction, home) }
```

with:

```kotlin
            SettingsPage.CHECKLIST -> ScrollingPage {
                ChecklistPage(states, status.models, status.samsung, onAction, { page = SettingsPage.PROVIDERS }, home)
            }
            SettingsPage.PROVIDERS -> ScrollingPage {
                val providers = data.providers
                if (providers != null) ProvidersPage(providers, home) else PageHeader(stringResource(R.string.providers_title), home)
            }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
    PageEntry(stringResource(R.string.settings_commands), stringResource(R.string.settings_commands_summary)) {
        onOpen(SettingsPage.COMMANDS)
    }
```

with:

```kotlin
    PageEntry(stringResource(R.string.settings_commands), stringResource(R.string.settings_commands_summary)) {
        onOpen(SettingsPage.COMMANDS)
    }
    PageEntry(stringResource(R.string.providers_title), stringResource(R.string.providers_summary)) {
        onOpen(SettingsPage.PROVIDERS)
    }
```

- [ ] **Step 5: Give the checklist step its state and its button**

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace:

```kotlin
 * shortcut, and of the accessibility button or gesture (section 8.6).
 */
data class SetupStatus(
```

with:

```kotlin
 * shortcut, and of the accessibility button or gesture (section 8.6). [providerReady]: the default provider profile
 * exists, its credential exists, and its borrowed sign-in is not switched off (design D18 of sub-project 4).
 */
data class SetupStatus(
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace:

```kotlin
    val buttonTarget: Boolean = false,
)
```

with:

```kotlin
    val buttonTarget: Boolean = false,
    val providerReady: Boolean = false,
)
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace:

```kotlin
            SetupStep.PROVIDER -> StepState.LATER
```

with:

```kotlin
            SetupStep.PROVIDER -> doneIf(status.providerReady)
```

In `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`, replace:

```kotlin
 * battery step also names Samsung's "Never sleeping apps".
 */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: ModelCheck,
    samsung: Boolean,
    onAction: (SetupAction) -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(stringResource(R.string.settings_checklist), onBack)
    for (step in SetupStep.entries) {
        StepCard(step, states.getValue(step), samsung, onAction) {
            if (step == SetupStep.COPY_MODELS) ModelProblems(models)
        }
    }
}
```

with:

```kotlin
 * battery step also names Samsung's "Never sleeping apps". [onOpenProviders] opens the page "Providers and profiles"
 * from the provider step (design D18 of sub-project 4).
 */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: ModelCheck,
    samsung: Boolean,
    onAction: (SetupAction) -> Unit,
    onOpenProviders: () -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(stringResource(R.string.settings_checklist), onBack)
    for (step in SetupStep.entries) {
        val state = states.getValue(step)
        StepCard(step, state, samsung, onAction) {
            if (step == SetupStep.COPY_MODELS) ModelProblems(models)
            if (step == SetupStep.PROVIDER && state in NEEDS_ACTION) {
                Button(onClick = onOpenProviders) { Text(stringResource(R.string.setup_provider_open)) }
            }
        }
    }
}
```

- [ ] **Step 6: Give the settings' data layer the providers**

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
```

with:

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
    suspend fun licenceText(licence: Licence): String
}
```

with:

```kotlin
    suspend fun licenceText(licence: Licence): String

    /** Whether the default provider profile can be used, for the checklist's step (design D18 of sub-project 4). */
    val providerReady: Flow<Boolean> get() = flowOf(false)

    /** What the page "Providers and profiles" reads and changes, or null for a fake without providers. */
    val providers: ProviderData? get() = null
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
    override val kokoroFailed: Flow<Boolean> = graph.kokoroFailed
```

with:

```kotlin
    override val kokoroFailed: Flow<Boolean> = graph.kokoroFailed

    override val providerReady: Flow<Boolean> get() = graph.providers.providerReady

    override val providers: ProviderData by lazy { StoredProviderData(graph.providers) }
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
            ?.let { pageRequest = PageRequest(it) }
```

with:

```kotlin
            ?.let { pageRequest = PageRequest(it, resumeAdd = intent.getBooleanExtra(EXTRA_RESUME_ADD, false)) }
```

- [ ] **Step 7: Add the page's texts**

In `app/src/main/res/values/strings.xml`, replace:

```xml
    <string name="setup_provider_title">Sign in to an AI provider and choose the models</string>
    <string name="setup_provider_detail">This step comes with Kestrel\'s provider settings.</string>
```

with:

```xml
    <string name="setup_provider_title">Add a provider profile and choose its models</string>
    <string name="setup_provider_detail">The step is done when the default profile can be used.</string>
```

In `app/src/main/res/values/strings.xml`, replace:

```xml
    <string name="about_licence_mit_summary">The licence of ONNX Runtime, piper-phonemize, JSON for Modern C++ and some speech models</string>
```

with:

```xml
    <string name="about_licence_mit_summary">The licence of ONNX Runtime, piper-phonemize, JSON for Modern C++, OpenClaw\'s provider catalog and some speech models</string>
```

In `app/src/main/res/values/strings.xml`, replace:

```xml
    <string name="signin_cancel">Cancel</string>
```

with:

```xml
    <string name="signin_cancel">Cancel</string>
    <string name="setup_provider_open">Open providers</string>
    <string name="providers_title">Providers and profiles</string>
    <string name="providers_summary">Sign-in, models, cost and allowance</string>
    <string name="providers_default">default</string>
    <string name="providers_models">Lightweight: %1$s. Versatile: %2$s.</string>
    <string name="providers_this_month">This month: %1$s</string>
    <string name="providers_last_month">Last month: %1$s</string>
    <string name="providers_cost_credits">%1$s AI Credits</string>
    <string name="providers_cost_usd">%1$s US dollars (%2$s credits)</string>
    <plurals name="providers_cost_tokens">
        <item quantity="one">%1$d token, paid by the plan</item>
        <item quantity="other">%1$d tokens, paid by the plan</item>
    </plurals>
    <string name="providers_add">Add a profile</string>
    <string name="providers_none">No profile yet. Add one, so that Kestrel\'s agent can work.</string>
    <string name="providers_switches">Borrowed sign-ins</string>
    <string name="providers_switch_copilot">GitHub Copilot sign-in</string>
    <string name="providers_switch_copilot_detail">Kestrel signs in with the GitHub client of GitHub\'s own Copilot apps and sends the headers of VS Code\'s Copilot Chat. GitHub\'s terms neither allow nor forbid this, and GitHub may suspend an account at any time.</string>
    <string name="providers_switch_xai">xAI (Grok) sign-in</string>
    <string name="providers_switch_xai_detail">Kestrel signs in with the Grok CLI\'s client and sends its requests through the Grok CLI\'s proxy with its headers. xAI\'s terms do not address this, and the subscription covers first-party use only. An xAI API key works without it.</string>
    <string name="providers_switch_minimax">MiniMax sign-in</string>
    <string name="providers_switch_minimax_detail">Kestrel signs in to the M Plan with the client that OpenClaw uses. MiniMax\'s terms neither allow nor forbid this. A MiniMax API key works without it.</string>
    <string name="providers_chatgpt_official">Uses OpenAI\'s official Sign in with ChatGPT for open-source apps, which borrows nothing.</string>
    <string name="providers_search">Search providers</string>
    <string name="providers_choose_method">How do you want to sign in to %1$s?</string>
    <string name="providers_key">API key</string>
    <string name="providers_base_url">Base URL</string>
    <string name="providers_base_url_missing">Type the base URL of your endpoint.</string>
    <string name="providers_base_url_bad">The base URL must start with https:// and have no user name, query or fragment.</string>
    <string name="providers_region">Region</string>
    <string name="providers_location">Location, such as global or us-central1</string>
    <string name="providers_location_bad">Type a location such as global, us, eu or us-central1.</string>
    <string name="providers_model_first">Model or deployment name</string>
    <string name="providers_model_first_detail">This provider has no list of models, so type the model, or for Azure the deployment name, before you check.</string>
    <string name="providers_server_address">Server address</string>
    <string name="providers_server_detail">For a server on your computer, use the computer\'s IP address, such as http://192.168.1.20:11434, and check the port that the server prints.</string>
    <string name="providers_server_bad">Kestrel accepts http:// only for a server on this phone or on your own network. Use https:// for any other server.</string>
    <string name="providers_server_key">Key (optional)</string>
    <string name="providers_unencrypted">The connection to this server is not encrypted.</string>
    <string name="providers_local_why">The server is on your local network, so Android must allow Kestrel to reach devices there. Tap \"Allow\" and answer Android\'s question, then tap Check again.</string>
    <string name="providers_local_allow">Allow</string>
    <string name="providers_aws_key_id">Access key ID</string>
    <string name="providers_aws_secret">Secret access key</string>
    <string name="providers_aws_session">Session token (optional)</string>
    <string name="providers_key_file">Choose the key file</string>
    <string name="providers_key_file_bad">This is not a Google service-account key file.</string>
    <string name="providers_key_file_ok">Key file read. Kestrel keeps the four fields that it needs and forgets the file.</string>
    <string name="providers_tenant">GHE.com tenant, such as octo.ghe.com</string>
    <string name="providers_tenant_bad">Type your GHE.com tenant, such as octo.ghe.com.</string>
    <string name="providers_sign_in">Sign in</string>
    <string name="providers_check">Check</string>
    <string name="providers_checking">Checking…</string>
    <string name="providers_code_title">Type this code on the sign-in page</string>
    <string name="providers_copy">Copy</string>
    <string name="providers_open_page">Open the page</string>
    <string name="providers_remaining">%1$d:%2$02d left</string>
    <string name="providers_cancel">Cancel</string>
    <string name="providers_waiting">Waiting for the sign-in…</string>
    <string name="providers_open_sign_in_page">Open the sign-in page</string>
    <string name="providers_no_browser">No browser can open the sign-in page. Install Chrome, or use an API key.</string>
    <string name="providers_try_again">Try again</string>
    <string name="providers_use_key">Use an API key</string>
    <string name="providers_allow_plan">Allow ChatGPT plan use</string>
    <string name="providers_register_account">Register with this account</string>
    <string name="providers_lightweight">Lightweight model</string>
    <string name="providers_versatile">Versatile model</string>
    <string name="providers_json_actions">Use JSON actions</string>
    <string name="providers_images">Accepts images</string>
    <string name="providers_tools">Handles tool calls</string>
    <string name="providers_name">Name</string>
    <string name="providers_make_default">Make default</string>
    <string name="providers_save">Save</string>
    <string name="providers_save_price">Save the price</string>
    <string name="providers_plan_dialog_title">You\'re using your ChatGPT plan</string>
    <string name="providers_plan_dialog_text">Kestrel\'s requests count against your ChatGPT plan\'s usage limits, and Kestrel\'s cost cap does not cover the plan. In \"Manage usage\" you set Kestrel\'s weekly limit and whether Kestrel may use your ChatGPT credits.</string>
    <string name="providers_manage_usage">Manage usage</string>
    <string name="providers_ok">OK</string>
    <string name="providers_using_plan">Using ChatGPT plan</string>
    <string name="providers_plan_cap">Your %1$s subscription pays for these requests, so Kestrel\'s cost cap does not cover them.</string>
    <string name="providers_plan_cap_chatgpt">Your ChatGPT plan pays for these requests, so Kestrel\'s cost cap does not cover them.</string>
    <string name="providers_plan_limit">Your ChatGPT plan\'s usage limit is reached.</string>
    <string name="providers_unknown_price">Kestrel does not know this model\'s price, so it counts 5 US dollars per million input tokens and 25 per million output tokens. Type the price to change this.</string>
    <string name="providers_price_input">Input, US dollars per million tokens</string>
    <string name="providers_price_output">Output, US dollars per million tokens</string>
    <string name="providers_refresh">Refresh models</string>
    <string name="providers_not_offered">not offered any more</string>
    <string name="providers_signed_in">Signed in on %1$s</string>
    <string name="providers_key_masked">Key %1$s</string>
    <string name="providers_sign_in_again">Sign in again</string>
    <string name="providers_change_key">Change key</string>
    <string name="providers_allowance">Check allowance</string>
    <string name="providers_allowance_premium">Copilot reports %1$s %% of your premium requests left.</string>
    <string name="providers_allowance_chat">Copilot reports %1$s %% of your chat requests left.</string>
    <string name="providers_allowance_none">Copilot reports no allowance for this plan. This month\'s credits above are Kestrel\'s own estimate.</string>
    <string name="providers_delete">Delete profile</string>
    <string name="providers_delete_confirm">Delete this profile and its sign-in or key?</string>
    <string name="providers_delete_yes">Delete</string>
    <string name="providers_open_chatgpt_settings">Open ChatGPT\'s settings</string>
    <string name="providers_rename">Rename</string>
```

- [ ] **Step 8: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with A₀ + 1 tests in `:app`: `SetupChecklistTest` has `theProviderStepIsDoneWhenTheDefaultProfileCanBeUsed` more, and `aNewInstallStillHasTheAndroidStepsToDo` now expects `TO_DO` for the provider step.

- [ ] **Step 9: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
- HTTPS is required, except for local-network or localhost servers that the owner adds. Kestrel shows a warning for those. The app's network security config lifts Android's own block on plain HTTP, so the gateway's rule is the one that decides. From Android 17, a server on the local network also needs the runtime permission `ACCESS_LOCAL_NETWORK` (B34). In plan 1A's device tests on the Android 17 emulator, servers on loopback and on the device's own local-network address both worked without it, so those tests do not show whether loopback is exempt; the cited page does not say.
```

with:

```markdown
- HTTPS is required, except for local-network or localhost servers that the owner adds. Kestrel shows a warning for those. The app's network security config lifts Android's own block on plain HTTP, so the gateway's rule is the one that decides. From Android 17, a server on the local network also needs the runtime permission `ACCESS_LOCAL_NETWORK` (B34). Kestrel asks for it, after one sentence that says why, when the owner checks such a server on the Providers page, and checks it before each request to such a server, which then fails at once with "Kestrel may not reach servers on the local network" instead of waiting for a timeout. Loopback needs no permission: prototype P5 showed on Android 17 that the app's own server and its connections to `127.0.0.1` work whether the permission is granted, revoked or not declared (section 19.1), so Kestrel never asks for it for a server on the phone or for a sign-in.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
| Expired sign-in | One silent refresh and retry, then "Your sign-in to <provider> expired. Open Kestrel to sign in again." |
```

with:

```markdown
| Expired sign-in | One silent refresh and retry, then "Your sign-in to <provider> expired. Open Kestrel to sign in again." A refresh that fails for a moment (no network, a timeout, a rate limit or a server error) keeps the sign-in |
| A provider that Kestrel cannot reach | Two retries, then Kestrel says that it cannot reach the provider |
| A sign-in that the owner switched off | Kestrel says that the sign-in is switched off |
| A server on the local network without `ACCESS_LOCAL_NETWORK` | Kestrel says that it may not reach the local network and asks the owner to open Kestrel |
| The ChatGPT plan's usage limit | Kestrel says that the plan's usage limit is reached; no retry |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
providers and profiles (sign-in, models, cost and remaining allowance)
```

with:

```markdown
providers and profiles (each profile's sign-in, models and cost, Copilot's remaining allowance, one switch per borrowed sign-in with the links to its provider's terms, and the ChatGPT plan's notices)
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
8. Sign in to a provider and choose the models.
```

with:

```markdown
8. Add a provider profile and choose its models. The step is done when the default profile can be used.
```

- [ ] **Step 10: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`, with `:core:providers` R₀ + 98, `:app` A₀ + 1 and `:core:privacy` P₀, lint without issues in every module (also no `ContextCastToActivity` and no `PluralsCandidate`), and no audit finding.

- [ ] **Step 11: Run the settings' device tests on the emulator**

Wait for the emulator lock and take it, as "Before you start" says, with the name `plan 4C task 7`. Delete it at the end of this step, also when a command fails: `Remove-Item .superpowers\sdd\emulator.lock`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.settings.ProvidersPageTest,dev.kestrel.app.settings.SettingsAppTest,dev.kestrel.app.MainActivityTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`, and the result file in `app\build\outputs\androidTest-results\connected\debug\` shows 0 failures: `ProvidersPageTest` 4 tests, `SettingsAppTest` with `theProviderStepOpensTheProvidersPage` more than before, and `MainActivityTest` with `theReturnFromASignInKeepsTheAddFlowOpen` more than before. The other device tests of `:app` are now O₀ + 12.

- [ ] **Step 12: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 13: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/providers/ProviderTexts.kt app/src/main/kotlin/dev/kestrel/app/settings/ProviderData.kt app/src/main/kotlin/dev/kestrel/app/settings/ProvidersPage.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/ProvidersPageTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(settings): add the page Providers and profiles with its add flow, sign-in and profile pages, and the checklist step" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The live tests

**Files:**
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/ProviderStandIn.kt`, `ProviderHooks.kt`, `scripts/provider-check.ps1`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, `app/build.gradle.kts`, `scripts/hook.ps1`, `AGENTS.md`
- Test: `scripts/tests/ProviderCheck.Tests.ps1`

**Interfaces:**
- Consumes: Task 6's `AppGraph.providers` (`stored`, `resolver`, `models`, `credentials`, `adapter`, `addSpending`, `localNetwork`); Tasks 2 and 5's `ProfileClient`, `ModelEntries`, `CopilotModels.model` and `styleFor`, `CopilotHeaders.forRequests`, `CopilotTargets.options`, `CopilotAllowance`; plan 4A's `NetworkPolicy.openTemporaryLocalServers`, `CatalogLoader.normalise`, `ApiKeyAuth`; plan 4B's adapters through `ProviderGraph.adapter`; `TestHooks.HookError`; `scripts\lib\Kestrel.ps1`'s `Use-KestrelToolchain` and `Assert-TestDevice`.
- Produces: `internal class ProviderHooks(graph: AppGraph)` with `suspend fun run(command: String, intent: Intent): String` and `companion object { val COMMANDS = setOf("PROVIDER_LIVE", "PROVIDER_STANDIN"); val CASES; const val PICTURE_WORD = "ORANGE"; fun picture(): ByteArray }`; `internal class ProviderStandIn : AutoCloseable` with `fun start()`, `val host: String`, `val base: String` and `companion object { const val NUMBER = 4721; const val MODELS }`; the hook commands `provider-live` (`-Case`, `-Model`, `-Headers`, `-MessagesPath`, `-Result`) and `provider-standin` (`-On`); `scripts\provider-check.ps1 -Serial <serial> [-Case <case>] [-Model <name>] [-Headers 'a=b;c=d'] [-MessagesPath <path>] [-TimeoutSeconds 240] [-PassThru]`, whose helpers `Get-LivePlan`, `ConvertFrom-LiveAnswer`, `Test-LivePassed`, `Test-ModelMissing` and `Format-LiveLine` the Pester tests load.

Design D20 and D22. A hook command ends after 20 s, so `provider-live` starts its case in `AppGraph.scope` and answers `started`, and `provider-live -Result` answers `running` until the case has ended, as `voice-echo -Result` does. It takes the model by name (by ID or by the normalised name), resolves the target for the default profile through `TargetResolver` without changing the profile, and refuses every default profile that is not a Copilot profile. Each case uses `ReasoningLevel.LOW` and 1,024 output tokens, because a reasoning model's reasoning counts toward the limit on Responses: a short text; a call of the test tool `get_number`, its result (4721) sent back, and an answer that must hold the number; the test picture, the word ORANGE drawn at run time, which the model must read; and JSON actions, where the tool call that comes back is the check. The allowance case sends Copilot's allowance call. The answers hold only `case`, `outcome`, `matched`, `style`, the token counts, whether the usage was estimated, the credits, `priceKnown` and the device's `millis`; a failure holds the error's kind, never a message or a body. `provider-standin -On true` starts a MockWebServer on the device that answers Copilot's three routes from the code (`ProviderStandIn.kt` copies Task 5's fixtures, because the APK has no JVM test resources), and holds `openTemporaryLocalServers` for its host, which is `localhost`; while it runs, `provider-live` builds its targets itself over the real `ProviderHttp` and skips discovery and the allowance, so no request leaves the device. The stand-in names only `start`, its dispatcher, `url` and `close`, so the audit finds nothing to flag. `provider-check.ps1` polls each case every 2 s for up to 4 minutes (a tool case's two calls with their retries take up to about 188 s), and after a timeout waits until the device has ended the case before it starts the next; it tries the next model of a list when the subscription does not offer one, runs Claude Haiku 5.5's cases once when it stood in for GPT-6 Luna, fails a case whose model has no known price, and prints one line per case. `provider-live` and `provider-standin` wait up to 5 s for the stored setting, so that a new process, as after `am crash`, has loaded it before they read the default profile.

- [ ] **Step 1: Write the failing Pester tests**

Create `scripts/tests/ProviderCheck.Tests.ps1`:

```powershell
# Tests of the helpers in scripts\provider-check.ps1. The script needs a device, so the tests load only its helper
# functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in
# PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\provider-check.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-LivePlan', 'ConvertFrom-LiveAnswer', 'Test-LivePassed', 'Test-ModelMissing', 'Format-LiveLine')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
}

Describe 'The helpers of the live cases of sub-project 4' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'plans each model''s cases, JSON actions with the lightweight model only, and the allowance last' {
        $plan = @(Get-LivePlan -Case '' -Model '')

        $plan.Count | Should -Be 14
        $plan[0].Case | Should -Be 'text'
        $plan[0].Models | Should -Be @('GPT-6 Luna', 'Claude Haiku 5.5')
        @($plan | Where-Object { $_.Case -eq 'json' }).Count | Should -Be 1
        ($plan | Where-Object { $_.Case -eq 'image' } | Select-Object -Last 1).Models | Should -Be @('Gemini 3.8 Flash', 'Gemini 4 Hydrogen')
        $plan[-1].Case | Should -Be 'allowance'
        $single = @(Get-LivePlan -Case 'tool' -Model 'Claude Haiku 5.5')
        $single.Count | Should -Be 1
        $single[0].Models | Should -Be @('Claude Haiku 5.5')
    }

    It 'reads an answer and passes it only when it matched with a known price' {
        $answer = ConvertFrom-LiveAnswer 'case=tool outcome=ok matched=true style=ANTHROPIC input=210 output=12 estimated=false credits=0.110 priceKnown=true millis=2310'

        $answer['style'] | Should -Be 'ANTHROPIC'
        Test-LivePassed $answer | Should -BeTrue
        Test-LivePassed (ConvertFrom-LiveAnswer 'case=tool outcome=ok matched=false priceKnown=true') | Should -BeFalse
        Test-LivePassed (ConvertFrom-LiveAnswer 'case=text outcome=ok matched=true priceKnown=false') | Should -BeFalse
        Test-LivePassed (ConvertFrom-LiveAnswer 'case=allowance outcome=ok quotas=2 premium=70 chat=100 millis=410') | Should -BeTrue
        { ConvertFrom-LiveAnswer 'running' } | Should -Throw 'Not an answer of provider-live*'
    }

    It 'tries the next model only when the subscription does not offer one, and writes one line per case' {
        Test-ModelMissing (ConvertFrom-LiveAnswer 'case=text outcome=failed error=the-subscription-does-not-offer-this-model') | Should -BeTrue
        Test-ModelMissing (ConvertFrom-LiveAnswer 'case=text outcome=failed error=Timeout') | Should -BeFalse

        Format-LiveLine -Case 'text' -Model 'GPT-6 Luna' -Answer (ConvertFrom-LiveAnswer 'case=text outcome=ok matched=true millis=900') |
            Should -BeExactly 'text (GPT-6 Luna): ok matched=true millis=900'
        Format-LiveLine -Case 'allowance' -Model '' -Answer (ConvertFrom-LiveAnswer 'case=allowance outcome=failed error=Unreachable') |
            Should -BeExactly 'allowance: failed error=Unreachable'
        $skipped = ConvertFrom-LiveAnswer 'case=allowance outcome=skipped standin=on'
        Test-LivePassed $skipped | Should -BeTrue
        Format-LiveLine -Case 'allowance' -Model '' -Answer $skipped | Should -BeExactly 'allowance: skipped standin=on'
    }
}
```

- [ ] **Step 2: Run the script tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: exit code 1. In both shells, Pester reports T₀ tests passed and the 4 tests of `ProviderCheck.Tests.ps1` failed, because `provider-check.ps1` does not exist ("Could not find file").

- [ ] **Step 3: Write the check script**

Create `scripts/provider-check.ps1`:

```powershell
# Runs the live cases of sub-project 4 (design D20) through the debug hook provider-live on one test device, and prints
# one line for each case: the case, the model, ok or failed, whether the answer matched, the style, the tokens, the
# credits, whether Kestrel knew the price, and the time that the device measured. Each case starts on the device and the
# script asks for its answer every 2 s, up to -TimeoutSeconds (4 minutes, longer than a tool case's two calls with their
# retries); after that it waits until the device has ended the case before it starts the next. Without -Case it runs the
# whole plan: for each
# Copilot model of the session, a short text, a tool call with its result, and an image; JSON actions with the
# lightweight model; and last the allowance. A model that the subscription does not offer is replaced by the next one of
# its list (GPT-6 Luna, then Claude Haiku 5.5; Gemini 3.8 Flash, then Gemini 4 Hydrogen); when Claude Haiku 5.5 stands in
# for the lightweight model, its own text, tool and image cases are not run twice. A case fails when the device
# reports a failure, when its answer did not match, or when Kestrel did not know the model's price. -Headers
# ('name=value;name=value') and -MessagesPath are the debug overrides for a retry within the owner's session. With
# provider-standin on, the cases go to the stand-in on the device and make no request off it. -PassThru returns one
# object for each case, for scripts\a71-providers.ps1. It exits with 1 when a case failed. It never prints a prompt, a
# reply, a code, a token or a key: the hook answers only outcomes, counts and times.
# It needs PowerShell 7.3 or newer, because it runs scripts\hook.ps1.
# Example: scripts\provider-check.ps1 -Serial emulator-5554 -Case text -Model 'GPT-6 Luna'
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('text', 'tool', 'image', 'json', 'allowance')][string]$Case,
    [string]$Model,
    [string]$Headers,
    [string]$MessagesPath,
    [int]$TimeoutSeconds = 240,
    [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

# The session's cases (design D20): each entry is a case and the models to try in order.
function Get-LivePlan([string]$Case, [string]$Model) {
    if ($Case) { return @([pscustomobject]@{ Case = $Case; Models = @(if ($Model) { $Model }) }) }
    $lightweight = @('GPT-6 Luna', 'Claude Haiku 5.5')
    $gemini = @('Gemini 3.8 Flash', 'Gemini 4 Hydrogen')
    $plan = foreach ($entry in @(
            @{ Models = $lightweight; Cases = @('text', 'tool', 'image', 'json') }
            @{ Models = @('GPT-6.1 Terra'); Cases = @('text', 'tool', 'image') }
            @{ Models = @('Claude Haiku 5.5'); Cases = @('text', 'tool', 'image') }
            @{ Models = $gemini; Cases = @('text', 'tool', 'image') }
        )) {
        foreach ($name in $entry.Cases) { [pscustomobject]@{ Case = $name; Models = $entry.Models } }
    }
    @($plan) + [pscustomobject]@{ Case = 'allowance'; Models = @() }
}

# The answer of provider-live -Result, such as 'case=text outcome=ok matched=true ...', as an ordered table.
function ConvertFrom-LiveAnswer([string]$Text) {
    $pairs = [ordered]@{}
    foreach ($pair in @("$Text".Trim() -split '\s+' | Where-Object { $_ })) {
        $name, $value = $pair -split '=', 2
        if (-not $name -or $null -eq $value) { throw "Not an answer of provider-live: '$Text'" }
        $pairs[$name] = $value
    }
    if (-not $pairs.Contains('outcome')) { throw "Not an answer of provider-live: '$Text'" }
    $pairs
}

# Whether a case passed: the device reported ok, the answer matched, and Kestrel knew the price (design D20). The
# stand-in skips the allowance, which counts as passed.
function Test-LivePassed($Answer) {
    if ($Answer['outcome'] -eq 'skipped') { return $true }
    if ($Answer['outcome'] -ne 'ok') { return $false }
    if ($Answer.Contains('matched') -and $Answer['matched'] -ne 'true') { return $false }
    if ($Answer.Contains('priceKnown') -and $Answer['priceKnown'] -ne 'true') { return $false }
    $true
}

# Whether the device said that the subscription does not offer the model, so that the next model of the list is tried.
function Test-ModelMissing($Answer) { $Answer['outcome'] -eq 'failed' -and $Answer['error'] -eq 'the-subscription-does-not-offer-this-model' }

# One line of the results, with only what the hook answered.
function Format-LiveLine([string]$Case, [string]$Model, $Answer) {
    $verdict = if ($Answer['outcome'] -eq 'skipped') { 'skipped' } elseif (Test-LivePassed $Answer) { 'ok' } else { 'failed' }
    $rest = @($Answer.Keys | Where-Object { $_ -notin 'case', 'outcome' } | ForEach-Object { "$_=$($Answer[$_])" }) -join ' '
    "$Case$(if ($Model) { " ($Model)" }): $verdict $rest".TrimEnd()
}

# Starts a case on the device and waits for its answer, up to $TimeoutSeconds.
function Invoke-LiveCase([string]$Case, [string]$Model, [string]$Headers, [string]$MessagesPath, [int]$TimeoutSeconds) {
    $with = @{ Case = $Case }
    if ($Model) { $with.Model = $Model }
    if ($Headers) { $with.Headers = $Headers }
    if ($MessagesPath) { $with.MessagesPath = $MessagesPath }
    $started = (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command provider-live @with) -join "`n"
    if ($started -ne 'started') { throw "provider-live did not start the case: $started" }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 2
        $answer = (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command provider-live -Result) -join "`n"
        if ($answer -ne 'running') { return ConvertFrom-LiveAnswer $answer }
    }
    # The case still runs on the device, and the next start would be refused while it does, so wait for its end.
    $drain = [Diagnostics.Stopwatch]::StartNew()
    while ($drain.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 2
        if (((& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command provider-live -Result) -join "`n") -ne 'running') { break }
    }
    [ordered]@{ case = $Case; outcome = 'failed'; error = 'timeout' }
}

Assert-TestDevice -Serial $Serial
$failed = 0
$done = @{}
$results = foreach ($entry in Get-LivePlan -Case $Case -Model $Model) {
    $models = @($entry.Models)
    if ($models.Count -eq 0) { $models = @('') }
    # A case that already ran with this model, as Claude Haiku 5.5's when it stood in for GPT-6 Luna, counts once.
    if ($models.Count -eq 1 -and $done.ContainsKey("$($entry.Case)|$($models[0])")) { continue }
    $answer = $null
    $used = $null
    foreach ($name in $models) {
        $answer = Invoke-LiveCase -Case $entry.Case -Model $name -Headers $Headers -MessagesPath $MessagesPath -TimeoutSeconds $TimeoutSeconds
        $used = $name
        if (-not (Test-ModelMissing $answer)) { break }
    }
    $done["$($entry.Case)|$used"] = $true
    $line = Format-LiveLine -Case $entry.Case -Model $used -Answer $answer
    Write-Host $line
    $passed = Test-LivePassed $answer
    if (-not $passed) { $failed++ }
    [pscustomobject]@{ Case = $entry.Case; Model = $used; Passed = $passed; Answer = $answer; Line = $line }
}
if ($PassThru) { $results }
if ($failed -gt 0) { exit 1 }
```

- [ ] **Step 4: Add the hooks' commands and parameters to hook.ps1**

In `scripts/hook.ps1`, replace:

```powershell
# Spike S1's location check (scripts\spike-s1.ps1 runs these):
```

with:

```powershell
# The live tests of sub-project 4 (scripts\provider-check.ps1 and scripts\a71-providers.ps1 run these):
#   provider-live -Case text|tool|image|json -Model 'name' [-Headers 'name=value;name=value'] [-MessagesPath path]
#                                           starts the case with the Copilot model of that name and the default
#                                           profile, which must be a Copilot profile, and answers started; -Headers and
#                                           -MessagesPath are debug overrides for a retry in the owner's session
#   provider-live -Case allowance           starts Copilot's allowance call for the default profile
#   provider-live -Result                   answers running until the case has ended, then its outcome, whether the
#                                           answer matched, the style, the tokens, the credits, whether the price was
#                                           known and the time on the device; never a prompt, a reply or a token
#   provider-standin [-On true|false]       answers whether the Copilot stand-in on the device runs and whether the
#                                           default profile is a Copilot profile (profile=none|copilot|other), after
#                                           starting or ending the stand-in; while it runs, provider-live sends its
#                                           cases to it and makes no request off the device
# Spike S1's location check (scripts\spike-s1.ps1 runs these):
```

In `scripts/hook.ps1`, replace:

```powershell
        'spike-results', 'files', 'watch', 'profiles', 'snapshots')][string]$Command,
```

with:

```powershell
        'spike-results', 'files', 'watch', 'profiles', 'snapshots', 'provider-live', 'provider-standin')][string]$Command,
```

In `scripts/hook.ps1`, replace:

```powershell
    [double]$Threshold = -1
)
```

with:

```powershell
    [double]$Threshold = -1,
    [ValidateSet('text', 'tool', 'image', 'json', 'allowance')][string]$Case,
    [string]$Model,
    [string]$Headers,
    [string]$MessagesPath
)
```

In `scripts/hook.ps1`, replace:

```powershell
if ($ScreenLabels) { $extras += '--ez screenLabels true' }
```

with:

```powershell
if ($ScreenLabels) { $extras += '--ez screenLabels true' }
if ($Case) { $extras += "--es case $(ConvertTo-ShellText $Case.ToLowerInvariant())" }
if ($Model) { $extras += "--es model $(ConvertTo-ShellText $Model)" }
if ($Headers) { $extras += "--es headers $(ConvertTo-ShellText $Headers)" }
if ($MessagesPath) { $extras += "--es messagesPath $(ConvertTo-ShellText $MessagesPath)" }
```

In `AGENTS.md`, replace:

```markdown
- Run device tests only through `scripts\connected-test.ps1`, `scripts\voice-test.ps1`, `scripts\command-test.ps1` (the instant commands, on the emulator only) or `scripts\listen-check.ps1`, as each plan says, and install only through `scripts\install.ps1`.
```

with:

```markdown
- Run device tests only through `scripts\connected-test.ps1`, `scripts\voice-test.ps1`, `scripts\command-test.ps1` (the instant commands, on the emulator only), `scripts\provider-check.ps1` (the providers' live cases, which call Copilot: only in the owner's session, or against the stand-in of `scripts\hook.ps1 provider-standin`) or `scripts\listen-check.ps1`, as each plan says, and install only through `scripts\install.ps1`.
```

- [ ] **Step 5: Run the script tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: exit code 0. In both shells, Pester reports T₀ + 4 tests passed and 0 failed, and PSScriptAnalyzer reports no finding for `hook.ps1`, `provider-check.ps1` and `ProviderCheck.Tests.ps1`.

- [ ] **Step 6: Write the stand-in and the hooks, and register them**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/ProviderStandIn.kt`:

```kotlin
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
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/ProviderHooks.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
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
            val bitmap = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
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
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, replace:

```kotlin
        if (command in SessionHooks.COMMANDS) return SessionHooks(graph, context).run(command, intent)
```

with:

```kotlin
        if (command in SessionHooks.COMMANDS) return SessionHooks(graph, context).run(command, intent)
        if (command in ProviderHooks.COMMANDS) return ProviderHooks(graph).run(command, intent)
```

In `app/build.gradle.kts`, replace:

```kotlin
    debugImplementation(libs.androidx.compose.ui.test.manifest)
```

with:

```kotlin
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // The Copilot stand-in of the debug hook provider-standin (design D20 of sub-project 4); release builds lack it.
    debugImplementation(libs.okhttp.mockwebserver3)
```

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 :app:assembleDebug check kestrelAudit`
Expected: `BUILD SUCCESSFUL`, with `:core:providers` R₀ + 98, `:app` A₀ + 1 and `:core:privacy` P₀, lint without issues, and the audit: `app\build\reports\kestrel-audit\network-apis-debug.txt` and `network-apis-release.txt` (`:app` is an Android module) list no finding, and the licence check accepts `mockwebserver3` (Apache-2.0, as OkHttp) in the debug runtime classpath.

- [ ] **Step 8: Run the live cases against the stand-in on the emulator**

Wait for the emulator lock and take it, as "Before you start" says, with the name `plan 4C task 8`. Delete it at the end of this step, also when a command fails: `Remove-Item .superpowers\sdd\emulator.lock`. If a command fails after the stand-in started, end it first: `scripts\hook.ps1 -Serial emulator-5554 provider-standin -On false`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\install.ps1 -Serial emulator-5554; scripts\hook.ps1 -Serial emulator-5554 provider-standin -On true`
Expected: the install ends with "Success", and the hook answers `standin=on profile=none`. (If it answers `profile=copilot` or `profile=other`, the emulator has a profile from earlier work; the stand-in does not use it, so go on.)

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\provider-check.ps1 -Serial emulator-5554; $LASTEXITCODE`
Expected: 14 lines, then `0`. The first 13 have the form `text (GPT-6 Luna): ok matched=true style=RESPONSES input=120 output=8 estimated=false credits=… priceKnown=true millis=… standin=on`: `text`, `tool`, `image` and `json` with GPT-6 Luna (style `RESPONSES`), `text`, `tool` and `image` with GPT-6.1 Terra (`RESPONSES`), with Claude Haiku 5.5 (`ANTHROPIC`) and with Gemini 3.8 Flash (`CHAT_COMPLETIONS`, with `estimated=true`, because Copilot's Chat Completions sends no usage in its stream); the last is `allowance: skipped standin=on`. The credits are what the call would have cost, but the stand-in's cases add nothing to the month's spending.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\hook.ps1 -Serial emulator-5554 provider-standin -On false`
Expected: `standin=off profile=none` (or the profile kind that the first command answered).

- [ ] **Step 9: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 10: Commit**

```powershell
git add app/src/debug/kotlin/dev/kestrel/app/debug/ProviderStandIn.kt app/src/debug/kotlin/dev/kestrel/app/debug/ProviderHooks.kt app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt app/build.gradle.kts scripts/hook.ps1 scripts/provider-check.ps1 scripts/tests/ProviderCheck.Tests.ps1 AGENTS.md
git commit -m "test(providers): add the live-test hooks with the Copilot stand-in, and provider-check.ps1" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: The session

**Files:**
- Create: `scripts/a71-providers.ps1`
- Modify: `README.md` (at the 4C marker), `docs/superpowers/specs/2026-11-12-kestrel-design.md` (18.4, 19, 19.1 and 20)
- Test: `scripts/tests/A71Providers.Tests.ps1`

**Interfaces:**
- Consumes: Task 8's `provider-check.ps1 -PassThru` (objects with `Case`, `Model`, `Passed`, `Answer` and `Line`), the hooks `provider-live` and `provider-standin`; Task 6's `CustomTabSignInTest` (with `kestrelRedirectCheck=true`); Task 4's `LoopbackServerTest`; plan 4A's `CredentialStoreTest`; `scripts\lib\Session.ps1`'s `Add-SessionResult`, `ConvertFrom-HookPairs`, `Get-UnpassedTests` and `Test-ClassPassed`; `scripts\lib\Kestrel.ps1`'s `Assert-TestDevice`, `Invoke-SettingsCommand`, `Get-Adb` and `$KestrelTestDevices`; `scripts\install.ps1`, `scripts\connected-test.ps1`.
- Produces: `scripts\a71-providers.ps1 [-Serial <A71>] [-EmulatorSerial emulator-5554] [-DryRun] [-Step <names>]`, whose helpers `Get-SessionSteps`, `Get-RetryOverride` and `ConvertFrom-StandInAnswer` the Pester tests load; the results file `corpus\owner\providers-<time>.txt` (`providers-dry-run-<time>.txt` for a dry run); the controller's optional overrides file `corpus\owner\provider-overrides.json`.

Design D20, built like plan 2E's `a71-session.ps1` on `scripts\lib\Session.ps1`, with the owner's lesson from sub-project 2's session: each prompt says exactly what the owner does, on which device, and what the screen shows before and after; the script then checks the device's state through `provider-standin` (the default profile is a Copilot profile, or none after the deletion) instead of trusting the Enter, and every time that it reports was measured on the device by `provider-live`. It asks before each step and before it reads each device's model, refuses every device but kestrel-api37 and the A71 (`Assert-TestDevice` without `-AllowDailyPhone`), ends the stand-in also after a failure or Ctrl+C, and stops Gradle when a step used it. Without `-DryRun`, `-Serial` must be the A71, and the session holds the emulator's lock `.superpowers\sdd\emulator.lock` (process rule 8a) until it ends. Before the Custom Tab tests, a71-tests asks the owner to unlock the A71 and turns on "Stay awake" (`svc power stayon usb`), which the session puts back at its end; it deletes earlier test results before each run of the device tests, and starts Kestrel again after them. a71-restart checks Kestrel's process ID before and after `am crash`, and says what the A71 may show. The steps, 44 minutes in all (about 45 with the owner's answers), of which about 8 need the owner's hands: `install` (5 minutes, without the owner), `emulator-signin` (3, with the owner, who approves the code on github.com on his computer or phone), `emulator-live` (8, without his hands), `a71-tests` (12: the credential store with the A71's Keystore and whether it takes the StrongBox path, the loopback server, and the Custom Tab with the redirect case through postman-echo.com in the A71's Chrome, which repeats P5's q1 and q3; the owner watches for a Local Network Access prompt and answers whether one came), `a71-signin` (3, with the owner, in a Custom Tab on the A71), `a71-live` (8), `a71-restart` (3: `am crash dev.kestrel`, not `am force-stop`, then one live case, so that the stored credential decrypts after a restart) and `end` (2: the owner deletes the emulator's Copilot profile on its profile page, because other work shares the emulator, and decides whether the A71 keeps its profile). A failed case gets one retry when the controller's `provider-overrides.json` names an override for its error kind (`{"BadRequest": {"Headers": "a=b", "MessagesPath": "/v1/messages"}}`); a failure that the retry does not fix is a finding for this plan's final fix wave. The dry run runs every step on kestrel-api37 without the owner: it skips the sign-ins and the waits, runs the live cases against the stand-in, runs the redirect case with the emulator's Chrome 145, and checks at the end that the stand-in is off and reports the default profile.

- [ ] **Step 1: Write the failing Pester tests**

Create `scripts/tests/A71Providers.Tests.ps1`:

```powershell
# Tests of the helpers in scripts\a71-providers.ps1, the owner's session of sub-project 4. The script needs devices, so
# the tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-providers.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Get-RetryOverride', 'ConvertFrom-StandInAnswer')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
}

Describe 'The helpers of the owner''s session of sub-project 4' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'takes the steps also as one text separated by commas, and refuses an unknown one' {
        $names = @('install', 'emulator-live', 'end')

        Get-SessionSteps -Step @('install,end') -Names $names | Should -Be @('install', 'end')
        @(Get-SessionSteps -Step @() -Names $names).Count | Should -Be 0
        { Get-SessionSteps -Step @('install,radio') -Names $names } | Should -Throw 'Unknown steps: radio.*'
    }

    It 'finds the controller''s override for a failure''s error kind' {
        $overrides = '{"Malformed": {"MessagesPath": "/v1/messages"}, "BadRequest": {"Headers": "Openai-Intent=conversation-edits"}, "Timeout": {}}' | ConvertFrom-Json

        (Get-RetryOverride -Overrides $overrides -ErrorText 'Malformed').MessagesPath | Should -Be '/v1/messages'
        (Get-RetryOverride -Overrides $overrides -ErrorText 'BadRequest(status=400,code=x)').Headers | Should -Be 'Openai-Intent=conversation-edits'
        Get-RetryOverride -Overrides $overrides -ErrorText 'Timeout' | Should -BeNullOrEmpty
        Get-RetryOverride -Overrides $overrides -ErrorText 'Unreachable' | Should -BeNullOrEmpty
        Get-RetryOverride -Overrides $null -ErrorText 'Malformed' | Should -BeNullOrEmpty
    }

    It 'reads the stand-in''s answer with the kind of the default profile' {
        $state = ConvertFrom-StandInAnswer 'standin=off profile=copilot'

        $state['standin'] | Should -Be 'off'
        $state['profile'] | Should -Be 'copilot'
        { ConvertFrom-StandInAnswer 'standin=on' } | Should -Throw 'Not an answer of provider-standin*'
    }
}
```

- [ ] **Step 2: Run the script tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: exit code 1. In both shells, Pester reports T₀ + 4 tests passed and the 4 tests of `A71Providers.Tests.ps1` failed, because `a71-providers.ps1` does not exist.

- [ ] **Step 3: Write the session script**

Create `scripts/a71-providers.ps1`:

```powershell
# The owner's session of sub-project 4 (design D20) on kestrel-api37 and the Galaxy A71: the Copilot sign-in on each,
# the live cases of scripts\provider-check.ps1 on each, the providers' device tests and the Custom Tab's redirect check
# with the A71's own Chrome (prototype P5's q1 and q3), the credentials after a restart of Kestrel's process, and the end,
# where the emulator's Copilot profile goes. Each prompt says what the owner does, on which device, and what the screen
# shows before and after; the script then checks the device's state through the hooks instead of trusting the Enter, and
# every time that it reports was measured on the device. It asks before each step, and before it reads each device's
# model. The steps and their estimates, which add up to 44 minutes (about 45 with the owner's answers), of which about 8
# need the owner's hands:
#   install         installs Kestrel's debug build on kestrel-api37 and on the A71 (5 minutes, without the owner)
#   emulator-signin the Copilot sign-in on kestrel-api37, which the owner approves on github.com on his computer or
#                   phone (3 minutes with the owner)
#   emulator-live   every live case on kestrel-api37 (8 minutes, without the owner's hands)
#   a71-tests       the credential store, the loopback server and the Custom Tab on the A71, with the redirect check
#                   through postman-echo.com in the A71's Chrome; whether the A71 has StrongBox (12 minutes, without the
#                   owner's hands, but he watches the A71's screen for a prompt)
#   a71-signin      the Copilot sign-in on the A71 in a Custom Tab, which the owner approves in it (3 minutes with the owner)
#   a71-live        every live case on the A71 (8 minutes)
#   a71-restart     Kestrel's process ended with 'am crash', started again, and one live case, so that the stored
#                   credential decrypts after a restart (3 minutes)
#   end             the owner deletes the emulator's Copilot profile, and decides whether the A71 keeps its profile
#                   (2 minutes with the owner)
# A failed case is recorded with its error kind, and gets one retry when corpus\owner\provider-overrides.json, which the
# controller writes before the session, names an override for that kind ({"<kind>": {"Headers": "a=b", "MessagesPath":
# "/x"}}); a failure that the retry does not fix is a finding for plan 4C's fix wave. The results go into
# corpus\owner\providers-<time>.txt, which git ignores; neither it nor the console ever hold a prompt, a reply, a code, a
# token or a key. The script refuses every device but the A71 and kestrel-api37, and -Serial must be the A71. While the
# Custom Tab tests run, the A71's screen stays on while it is plugged in ("Stay awake"), and the setting is put back at
# the end. A real session takes the emulator's lock .superpowers\sdd\emulator.lock (process rule 8a), because other work
# shares the emulator, and deletes it at the end. -DryRun runs every step on kestrel-api37 without the owner, inside the
# lock that the plan's step holds: it skips the sign-ins and the waits for the owner, runs the live cases against the hook
# provider-standin's Copilot stand-in on the device, runs the redirect check with the emulator's Chrome, and checks at the
# end that the stand-in is off and reports the default profile. -Step runs only the named steps, also as one text
# separated by commas.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-providers.ps1 -Serial R58N12345AB -EmulatorSerial emulator-5554
#Requires -Version 7.3
param(
    [string]$Serial,
    [string]$EmulatorSerial = 'emulator-5554',
    [switch]$DryRun,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
Use-KestrelToolchain

$stepNames = @('install', 'emulator-signin', 'emulator-live', 'a71-tests', 'a71-signin', 'a71-live', 'a71-restart', 'end')

# The names of -Step, also as one text separated by commas, as pwsh -File passes them; an unknown name stops the script.
function Get-SessionSteps([string[]]$Step, [string[]]$Names) {
    $chosen = @($Step | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $unknown = @($chosen | Where-Object { $_ -notin $Names })
    if ($unknown) { throw "Unknown steps: $($unknown -join ', '). The steps are: $($Names -join ', ')." }
    $chosen
}

# The override that the controller prepared for a failed case's error kind, such as 'BadRequest' from
# 'BadRequest(status=400,code=x)', as parameters of provider-check.ps1, or $null.
function Get-RetryOverride($Overrides, [string]$ErrorText) {
    if (-not $Overrides -or -not $ErrorText) { return $null }
    $kind = ($ErrorText -split '\(')[0]
    $entry = $Overrides.PSObject.Properties | Where-Object { $_.Name -eq $kind } | Select-Object -First 1
    if (-not $entry) { return $null }
    $with = @{}
    foreach ($name in 'Headers', 'MessagesPath') {
        $value = $entry.Value.PSObject.Properties[$name]
        if ($value -and $value.Value) { $with[$name] = "$($value.Value)" }
    }
    if ($with.Count -eq 0) { return $null }
    $with
}

# The answer of provider-standin, such as 'standin=off profile=copilot', as an ordered table.
function ConvertFrom-StandInAnswer([string]$Text) {
    $pairs = ConvertFrom-HookPairs $Text
    if (-not $pairs.Contains('standin') -or -not $pairs.Contains('profile')) { throw "Not an answer of provider-standin: '$Text'" }
    $pairs
}

$Step = @(Get-SessionSteps -Step $Step -Names $stepNames)
if ($DryRun -and $Serial -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }
if (-not $DryRun -and -not $Serial) { throw 'Give the A71''s serial with -Serial.' }
# In a dry run, the A71's steps run on kestrel-api37 too.
if ($DryRun) { $Serial = $EmulatorSerial }

$owner = Join-Path $PSScriptRoot '..\corpus\owner'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = Join-Path $owner "providers-$(if ($DryRun) { 'dry-run-' })$stamp.txt"
$overridesFile = Join-Path $owner 'provider-overrides.json'
$overrides = if (Test-Path -LiteralPath $overridesFile) { Get-Content -LiteralPath $overridesFile -Raw | ConvertFrom-Json } else { $null }
$adb = Get-Adb
$gradleUsed = $false
$standInDevices = [System.Collections.Generic.List[string]]::new()
# "Stay awake" of the A71 as it was before a71-tests changed it, or $null when it was not changed.
$stayOn = $null
$lockFile = Join-Path $PSScriptRoot '..\.superpowers\sdd\emulator.lock'
$lockTaken = $false

# Asks the owner to do something, and returns 'go' after Enter or 'skip' after s. q stops the session. In a dry run it
# answers $DryRunAnswer itself.
function Request-Owner([string]$Prompt, [string]$DryRunAnswer = 'skip') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    $answer = (Read-Host $Prompt).Trim().ToLowerInvariant()
    if ($answer -eq 'q') { throw 'The owner stopped the session.' }
    if ($answer -in 's', 'skip') { 'skip' } else { 'go' }
}

function Read-OwnerAnswer([string]$Prompt, [string]$DryRunAnswer) {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    (Read-Host $Prompt).Trim().ToLowerInvariant()
}

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

function Invoke-Hook([string]$Device, [string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Device -Command $Command @With) -join "`n"
}

# What the device's Kestrel says about the stand-in and the default profile (none, copilot or other).
function Get-StandIn([string]$Device) { ConvertFrom-StandInAnswer (Invoke-Hook $Device provider-standin) }

# Waits up to $Seconds until the default profile of the device's Kestrel is of $Kind. Returns whether it is.
function Wait-Profile([string]$Device, [string]$Kind, [int]$Seconds = 30) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Get-StandIn $Device)['profile'] -eq $Kind) { return $true }
        Start-Sleep -Seconds 2
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# Opens Kestrel's page "Providers and profiles" on the device.
function Open-Providers([string]$Device) {
    Invoke-SettingsCommand -Serial $Device -Command 'am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE PROVIDERS' | Out-Null
}

# Kestrel's process ID on the device, or '' when it does not run.
function Get-KestrelPid([string]$Device) { ((Invoke-SettingsCommand -Serial $Device -Command 'pidof dev.kestrel || true') -join '').Trim() }

# Deletes an earlier run's results of the connected tests, such as the emulator's, so that they never count as the A71's.
function Clear-TestResults([string]$Folder) {
    if (Test-Path -LiteralPath $Folder) { Remove-Item -LiteralPath $Folder -Recurse -Force }
}

# The live cases on the device, with the stand-in in a dry run, and one retry with the controller's override per failure.
# Returns provider-check.ps1's results. provider-check.ps1 prints each line itself, so the lines go only to the file here.
function Invoke-Live([string]$StepName, [string]$Device, [string]$Case, [string]$Model) {
    if ($DryRun) {
        Invoke-Hook $Device provider-standin @{ On = 'true' } | Out-Null
        if (-not $standInDevices.Contains($Device)) { $standInDevices.Add($Device) }
    }
    $with = @{ Serial = $Device; PassThru = $true }
    if ($Case) { $with.Case = $Case }
    if ($Model) { $with.Model = $Model }
    $results = @(& "$PSScriptRoot\provider-check.ps1" @with | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['Line'] })
    foreach ($result in $results) {
        Add-SessionResult -Path $resultsFile -Step $StepName -Text $result.Line
        if ($result.Passed) { continue }
        $retry = Get-RetryOverride -Overrides $overrides -ErrorText $result.Answer['error']
        if (-not $retry) { continue }
        $again = @(& "$PSScriptRoot\provider-check.ps1" -Serial $Device -Case $result.Case -Model $result.Model -PassThru @retry) |
            Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['Line'] }
        # provider-check.ps1 printed the retry's line itself, so it goes only to the file here.
        foreach ($line in $again) { Add-SessionResult -Path $resultsFile -Step $StepName -Text "retry with $(@($retry.Keys) -join ' and '): $($line.Line)" }
    }
    if ($DryRun) { Invoke-Hook $Device provider-standin @{ On = 'false' } | Out-Null }
    $results
}

function Invoke-InstallStep {
    $script:gradleUsed = $true
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        & "$PSScriptRoot\install.ps1" -Serial $device
        if ($LASTEXITCODE -ne 0) { throw "scripts\install.ps1 failed on $device." }
        Add-Result install "Kestrel installed on $(if ($device -eq $EmulatorSerial) { 'kestrel-api37' } else { 'the A71' })"
    }
}

function Invoke-SignIn([string]$StepName, [string]$Device, [string]$Prompt) {
    if ($DryRun) {
        Add-Result $StepName 'skipped in a dry run: it needs the owner''s GitHub account'
        return
    }
    Open-Providers $Device
    if ((Request-Owner $Prompt) -eq 'skip') {
        Add-Result $StepName 'skipped by the owner'
        return
    }
    $found = Wait-Profile -Device $Device -Kind copilot
    Add-Result $StepName $(if ($found) { 'the default profile is a Copilot profile' } else { 'failed: Kestrel has no default Copilot profile' })
}

function Invoke-EmulatorSignInStep {
    Invoke-SignIn emulator-signin $EmulatorSerial ('On kestrel-api37''s screen, Kestrel''s page "Providers and profiles" is open. Tap "Add a profile", type Copilot in the search field, tap "Copilot", then "GitHub Copilot", then "Sign in". The page shows a code of 8 letters and digits with a hyphen, such as WDJB-MJHT, in large type, with a countdown. On your computer or phone, open github.com/login/device, sign in to GitHub if it asks, type that code and approve. A few seconds after GitHub accepts, the emulator''s page shows "Checking' + [char]0x2026 + '" and then the two lists of models, with a check mark at the lightweight and at the versatile model (GPT-6 Luna and GPT-6.1 Terra when the subscription offers them). Change nothing, scroll to the end of the page and tap "Save"; the page shows the list of profiles with "Copilot (default)". Press Enter when it does, or s to skip.')
}

function Invoke-A71SignInStep {
    Invoke-SignIn a71-signin $Serial ('Now on the A71: unlock it. Kestrel''s page "Providers and profiles" is open. Tap "Add a profile", type Copilot, tap "Copilot", then "GitHub Copilot", then "Sign in". The page shows a code in large type with a countdown. Tap "Copy", then "Open the page": Chrome opens github.com/login/device inside Kestrel, with a close button (X) at its top left. Sign in to GitHub if it asks, paste the code into the field and approve. A few seconds after GitHub accepts, Kestrel''s page comes back by itself and shows "Checking' + [char]0x2026 + '", then the two lists of models; if Chrome stays in front, tap its X. Scroll to the end of the page and tap "Save"; the page shows "Copilot (default)". Press Enter when it does, or s to skip.')
}

function Invoke-EmulatorLiveStep { Invoke-Live emulator-live $EmulatorSerial | Out-Null }

function Invoke-A71LiveStep { Invoke-Live a71-live $Serial | Out-Null }

function Invoke-A71TestsStep {
    $script:gradleUsed = $true
    # The Custom Tab tests need an unlocked, awake screen, and the builds before them take longer than the screen timeout.
    if ($null -eq $script:stayOn) {
        $script:stayOn = ((Invoke-SettingsCommand -Serial $Serial -Command 'settings get global stay_on_while_plugged_in') -join '').Trim()
        Invoke-SettingsCommand -Serial $Serial -Command 'svc power stayon usb' | Out-Null
    }
    if ((Request-Owner 'Unlock the A71 now and leave it on its home screen. Its screen stays on while it is plugged in, until the session ends. For the next few minutes Gradle builds and installs the tests, and the A71 shows nothing new. Press Enter when the A71 shows its home screen.' 'go') -eq 'skip') {
        Add-Result a71-tests 'skipped by the owner'
        return
    }
    $strongBox = "$(& $adb -s $Serial shell pm has-feature android.hardware.strongbox_keystore)".Trim()
    Add-Result a71-tests "StrongBox: $(if ($strongBox -eq 'true') { 'yes, the credentials key takes the StrongBox path' } else { 'no, the credentials key takes the path without StrongBox' })"
    $folder = Join-Path $PSScriptRoot '..\core\privacy\build\outputs\androidTest-results\connected'
    Clear-TestResults $folder
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':core:privacy:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.CredentialStoreTest,dev.kestrel.privacy.LoopbackServerTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    Add-Result a71-tests "CredentialStoreTest: $(if (Test-ClassPassed $folder CredentialStoreTest) { 'passed' } else { 'failed' }); LoopbackServerTest: $(if (Test-ClassPassed $folder LoopbackServerTest) { 'passed' } else { 'failed' })"
    if ((Request-Owner 'Watch the A71''s screen now. Kestrel''s settings open, and Chrome opens three times inside Kestrel: once at a page of Kestrel''s own, and twice at postman-echo.com, which sends Chrome on to Kestrel at once; each time the page says "Kestrel received the sign-in. You can close this tab." and closes after a few seconds. Do not touch the phone. If Chrome asks whether it may find or connect to devices on your local network, do not answer; remember it. It takes about 3 minutes. Press Enter to start.' 'go') -eq 'skip') {
        Add-Result a71-tests 'the Custom Tab tests: skipped by the owner'
        return
    }
    $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    Clear-TestResults $results
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':app:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.providers.CustomTabSignInTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelRedirectCheck=true' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    $failed = @(Get-UnpassedTests $results | Where-Object { $_ -like 'CustomTabSignInTest#*' })
    Add-Result a71-tests "CustomTabSignInTest: $(if ($failed) { $failed -join ', ' } else { if (Test-ClassPassed $results CustomTabSignInTest) { 'passed, with the redirect to 127.0.0.1 and to localhost' } else { 'not run' } })"
    $prompt = Read-OwnerAnswer 'Did Chrome ask about devices on your local network? Type y or n and press Enter.' 'n'
    Add-Result a71-tests "Chrome asked about the local network: $(if ($prompt -eq 'y') { 'yes' } else { 'no' })"
    # connected-test.ps1 ends with 'am force-stop', and a stopped app gets no hook broadcast, so Kestrel starts again.
    Open-Providers $Serial
    Start-Sleep -Seconds 3
}

function Invoke-A71RestartStep {
    if (-not $DryRun -and (Get-StandIn $Serial)['profile'] -ne 'copilot') {
        Add-Result a71-restart 'skipped: the A71 has no default Copilot profile'
        return
    }
    if ((Request-Owner 'On the A71: Kestrel''s process ends now and starts again. For a moment the A71 may show "Kestrel has stopped" or nothing new; then Kestrel''s page "Providers and profiles" opens. Do not touch the phone. Press Enter to start.' 'go') -eq 'skip') {
        Add-Result a71-restart 'skipped by the owner'
        return
    }
    $before = Get-KestrelPid $Serial
    Invoke-SettingsCommand -Serial $Serial -Command 'am crash dev.kestrel' | Out-Null
    Start-Sleep -Seconds 3
    Open-Providers $Serial
    Start-Sleep -Seconds 3
    $after = Get-KestrelPid $Serial
    if (-not $before -or -not $after -or $before -eq $after) {
        Add-Result a71-restart 'failed: Kestrel''s process did not end and start again'
        return
    }
    Add-Result a71-restart 'Kestrel''s process ended with am crash and started again with another process ID'
    # As in the other live steps, Claude Haiku 5.5 stands in when the subscription does not offer GPT-6 Luna.
    $first = @(Invoke-Live a71-restart $Serial 'text' 'GPT-6 Luna')
    if ($first.Count -gt 0 -and $first[0].Answer['error'] -eq 'the-subscription-does-not-offer-this-model') {
        Invoke-Live a71-restart $Serial 'text' 'Claude Haiku 5.5' | Out-Null
    }
}

function Invoke-EndStep {
    if ($DryRun) {
        $state = Get-StandIn $EmulatorSerial
        Add-Result end "the stand-in is $($state['standin']), and the default profile is $($state['profile'])"
        if ($state['standin'] -ne 'off') { throw 'The stand-in still runs.' }
        return
    }
    Open-Providers $EmulatorSerial
    if ((Request-Owner 'On kestrel-api37: the page "Providers and profiles" is open. Tap the profile "Copilot (default)", scroll to the end, tap "Delete profile" and then "Delete". The page shows the list again, with no profile. Press Enter when it does.' 'go') -ne 'skip') {
        Add-Result end $(if (Wait-Profile -Device $EmulatorSerial -Kind none) { 'the emulator''s Copilot profile is deleted' } else { 'failed: kestrel-api37 still has a default profile' })
    }
    $keep = Read-OwnerAnswer 'Should the A71 keep its Copilot profile for sub-project 5? Type y to keep it or n to delete it, and press Enter.' 'y'
    if ($keep -eq 'y') {
        Add-Result end 'the A71 keeps its Copilot profile'
        return
    }
    Open-Providers $Serial
    Request-Owner 'On the A71: unlock it. Kestrel''s page "Providers and profiles" is open. Tap the profile "Copilot (default)", scroll to the end, tap "Delete profile" and then "Delete". The page shows the list again, with no profile. Press Enter when it does.' 'go' | Out-Null
    Add-Result end $(if (Wait-Profile -Device $Serial -Kind none) { 'the A71''s Copilot profile is deleted' } else { 'failed: the A71 still has a default profile' })
}

$plan = @(
    @{ Name = 'install'; Minutes = 5; Title = 'install Kestrel on kestrel-api37 and the A71; Kestrel closes on both if it is open, and the A71 shows nothing else; you may leave'; Run = 'Invoke-InstallStep' }
    @{ Name = 'emulator-signin'; Minutes = 3; Title = 'the Copilot sign-in on kestrel-api37, approved on github.com'; Run = 'Invoke-EmulatorSignInStep' }
    @{ Name = 'emulator-live'; Minutes = 8; Title = 'every live case on kestrel-api37; no hands; the screens show nothing new, except that the line "This month" of the Copilot profile rises as the cases run'; Run = 'Invoke-EmulatorLiveStep' }
    @{ Name = 'a71-tests'; Minutes = 12; Title = 'the device tests and the redirect check on the A71; you watch its screen'; Run = 'Invoke-A71TestsStep' }
    @{ Name = 'a71-signin'; Minutes = 3; Title = 'the Copilot sign-in on the A71 in Chrome inside Kestrel'; Run = 'Invoke-A71SignInStep' }
    @{ Name = 'a71-live'; Minutes = 8; Title = 'every live case on the A71; no hands; the screens show nothing new, except that the line "This month" of the Copilot profile rises as the cases run'; Run = 'Invoke-A71LiveStep' }
    @{ Name = 'a71-restart'; Minutes = 3; Title = 'the stored credential after a restart of Kestrel on the A71'; Run = 'Invoke-A71RestartStep' }
    @{ Name = 'end'; Minutes = 2; Title = 'the emulator''s Copilot profile goes; you decide about the A71''s'; Run = 'Invoke-EndStep' }
)

$completed = $false
$stopped = $false
try {
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        if ((Request-Owner "The session first reads the model of the device $device, to check that it is kestrel-api37 or the Galaxy A71. The device shows nothing new. Press Enter to go ahead, or q to stop." 'go') -ne 'go') { throw 'The owner stopped the session.' }
        # Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this
        # script never passes.
        Assert-TestDevice -Serial $device
    }
    if ($KestrelTestDevices[$EmulatorSerial].Pattern -ne 'kestrel-api37') { throw "$EmulatorSerial is not kestrel-api37." }
    # Without -DryRun, the A71's steps must not run on the emulator.
    if (-not $DryRun -and $KestrelTestDevices[$Serial].Pattern -ne 'SM-A715F*') { throw "$Serial is not the Galaxy A71." }
    # Other work shares the emulator, so the real session holds its lock (process rule 8a); the dry run runs inside the
    # plan step's lock.
    if (-not $DryRun -and (Test-Path -LiteralPath (Split-Path $lockFile))) {
        $deadline = (Get-Date).AddMinutes(60)
        while (Test-Path -LiteralPath $lockFile) {
            if ((Get-Date) -gt $deadline) { throw 'The emulator lock still exists after 60 minutes.' }
            Write-Host 'Another task uses the emulator; the session waits for it and checks again in 2 minutes.'
            Start-Sleep -Seconds 120
        }
        Set-Content -LiteralPath $lockFile -Value 'a71-providers session'
        $lockTaken = $true
    }
    Add-Result session "started$(if ($DryRun) { ' as a dry run on kestrel-api37' })"
    foreach ($entry in $plan) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        if ((Request-Owner "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title). Press Enter to run it, s to skip it, or q to stop the session." 'go') -eq 'skip') {
            Add-Result $entry.Name 'skipped by the owner'
            continue
        }
        try {
            & $entry.Run
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            # The scripts' and hooks' errors name only steps, files and classes, never a prompt, a reply or a key.
            Add-Result $entry.Name "failed: $($_.Exception.Message)"
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $($_.Exception.Message)"
} finally {
    # The stand-in of a dry run ends, also after a failure or Ctrl+C.
    foreach ($device in $standInDevices) {
        try { Invoke-Hook $device provider-standin @{ On = 'false' } | Out-Null } catch { Write-Warning "Could not end the stand-in on $device. Run: scripts\hook.ps1 -Serial $device provider-standin -On false" }
    }
    # "Stay awake" as it was before a71-tests.
    if ($null -ne $stayOn) {
        $command = if ($stayOn -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $stayOn" }
        try { Invoke-SettingsCommand -Serial $Serial -Command $command | Out-Null } catch { Write-Warning "Could not put back ""Stay awake"" on $Serial. Run: & '$adb' -s $Serial shell $command" }
    }
    if ($lockTaken) { Remove-Item -LiteralPath $lockFile -ErrorAction SilentlyContinue }
    if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
    if ($gradleUsed) { & "$PSScriptRoot\gradle.ps1" --stop | Out-Null }
    if (Test-Path -LiteralPath $resultsFile) { Write-Host "The results are in $((Resolve-Path -LiteralPath $resultsFile).Path)." }
    if (-not ($completed -or $stopped)) {
        if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
        exit 1
    }
}
if ($stopped) { exit 1 }
```

- [ ] **Step 4: Run the script tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: exit code 0. In both shells, Pester reports T₀ + 8 tests passed and 0 failed, and PSScriptAnalyzer reports no finding.

- [ ] **Step 5: Run all device tests of the providers' modules on the emulator**

Wait for the emulator lock and take it, as "Before you start" says, with the name `plan 4C task 9`. Keep it until the end of Step 6, and delete it then, also when a command fails: `Remove-Item .superpowers\sdd\emulator.lock`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`
Expected: `BUILD SUCCESSFUL`. The result files show 0 failures: Q₀ + 6 tests in `:core:privacy`, and O₀ + 12 in `:app`, of which `CustomTabSignInTest`'s redirect case and the instant commands' device tests (which only `scripts\command-test.ps1` runs) are skipped.

- [ ] **Step 6: Run the session's dry run on the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\a71-providers.ps1 -DryRun -EmulatorSerial emulator-5554; $LASTEXITCODE`
Expected (about 25 minutes, most of it the install and the device tests): every prompt is printed with `[dry run]` and answered by the script, and the last lines are "The results are in …\corpus\owner\providers-dry-run-<time>.txt." and `0`. The results file holds, in this order: `session: started as a dry run on kestrel-api37`; `install: Kestrel installed on kestrel-api37`; `emulator-signin: skipped in a dry run: it needs the owner's GitHub account`; 14 `emulator-live` lines as in Task 8 Step 8, each `ok` with `standin=on`, the last `allowance: skipped standin=on`; `a71-tests: StrongBox: no, the credentials key takes the path without StrongBox` (after the prompt to unlock the A71, which the dry run answers itself), `a71-tests: CredentialStoreTest: passed; LoopbackServerTest: passed`, `a71-tests: CustomTabSignInTest: passed, with the redirect to 127.0.0.1 and to localhost` and `a71-tests: Chrome asked about the local network: no`; `a71-signin: skipped in a dry run: it needs the owner's GitHub account`; 14 `a71-live` lines as above; `a71-restart: Kestrel's process ended with am crash and started again with another process ID` and one `a71-restart` line `text (GPT-6 Luna): ok … standin=on`; and `end: the stand-in is off, and the default profile is none`. Each live line is printed once on the console, by `provider-check.ps1`. No line holds a prompt, a reply, a code, a token or a key. If the redirect case fails, the emulator's Chrome or postman-echo.com changed: report it to the controller with the failing test's name, because D10's fallback is the controller's decision.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\hook.ps1 -Serial emulator-5554 provider-standin`
Expected: `standin=off profile=none`: the dry run left the emulator as it found it.

- [ ] **Step 7: Describe the sign-ins and the session in the README**

In `README.md`, replace:

```markdown
<!-- Plan 4C: sign-in, Copilot and the session -->
```

with:

```markdown
Profiles are set up on the page "Providers and profiles" of Kestrel's settings. A profile signs in with an API key; with a device code (GitHub Copilot, xAI and MiniMax: the page shows a code, and you approve it in a browser on any device); with a browser sign-in in a Custom Tab (the ChatGPT plan, through OpenAI's Sign in with ChatGPT for open-source apps, and OpenRouter), which redirects to a temporary server on the phone's loopback address; with an imported credential (Bedrock's keys, or a Vertex service-account file, of which Kestrel keeps only the four fields that it needs); or with a server address (Ollama, LM Studio and the other local servers). Every sign-in runs in a foreground service whose notification has a Cancel action. "Check" tries the profile before it is saved, and the page then offers the models for the lightweight and the versatile role. The sign-ins of Copilot, xAI and MiniMax borrow another program's client, so each has a switch on the page with the links to its provider's terms; only Copilot's is on until you turn the others on. The ChatGPT plan's sign-in and requests are tested against mock servers only, so whether OpenAI accepts Kestrel's registration is untested. Each profile's page shows its models, the cost this month and last month, and for Copilot the remaining allowance when you tap "Check allowance".

Copilot is the only provider that is tested live, and only in the owner's session. `scripts\provider-check.ps1 -Serial <serial>` runs the live cases through the debug hook `provider-live` with the default Copilot profile: for each model of the session, a short text, a tool call with its result, and an image; JSON actions with the lightweight model; and the allowance. They cost about 2 to 4 AI Credits in all. `scripts\a71-providers.ps1 -Serial <A71's serial>` is the owner's session on kestrel-api37 and the A71, about 45 minutes, of which about 8 need your hands; `-DryRun` runs it on kestrel-api37 against a Copilot stand-in on the device (`scripts\hook.ps1 provider-standin`), without a request to any provider. Its results go into `corpus\owner`, which git ignores.

<!-- Plan 4C: sign-in, Copilot and the session -->
```

- [ ] **Step 8: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
- **Scope:** `:tools:catalog-sync` and the `catalog/` files; the eight request styles; the sign-in methods; model discovery; profiles and credentials; the Copilot specifics and cost estimation; the connection to the network allowlist; spike S6.
- **Done when:** every request style and sign-in method passes its mock-server tests, and live tests with the owner's Copilot subscription pass for the lightweight and versatile models, including tool calls and image input.
```

with:

```markdown
- **Scope:** `:tools:catalog-sync` and the `catalog/` files; the eight request styles; the sign-in methods, with the sign-in foreground service, the Custom Tab and the Providers page; model discovery; profiles and credentials; the Copilot specifics and cost estimation; the connection to the network allowlist; spike S6.
- **Done when:** every request style and every sign-in method that Kestrel offers passes its mock-server tests; the providers' device tests pass on kestrel-api37; the live cases of `scripts/provider-check.ps1` pass with the owner's Copilot subscription on kestrel-api37 and on the A71, for the lightweight and the versatile model and for one Claude and one Gemini model, with a tool call and its result, image input and JSON actions; the ChatGPT plan's sign-in and requests pass their mock-server tests, without a live check; the A71 runs the Custom Tab's redirect case with its own Chrome; and the results of prototype P5 and of the owner's session are in section 19.1 and the README.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
| S6 | Does browser sign-in with a redirect to 127.0.0.1 work from a Custom Tab (OpenRouter, Chutes, Codex)? | Emulator | Device code where the provider offers it, otherwise an API key |
```

with:

```markdown
| S6 | Does browser sign-in with a redirect to a loopback address work from a Custom Tab (OpenRouter and Sign in with ChatGPT)? | Emulator, A71 | An API key for both |
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
## 20. Risks and open questions
```

with:

```markdown
- **S6 on the emulator (prototype P5, sub-project 4).** On kestrel-api37 (Android 17, Chrome 145), a redirect (302) from a public HTTPS page, postman-echo.com's `/redirect-to`, to `http://127.0.0.1:1455/…` in a Custom Tab reached the app's server about 3 s after the tab opened, with no Local Network Access prompt and no error. For `http://localhost:<port>`, Chrome connected to `::1` first and fell back to `127.0.0.1`. `ACCESS_LOCAL_NETWORK` did not apply to loopback, whether it was granted, revoked or not declared, both for the app's own server and for its connections to `127.0.0.1`. Without a foreground service, Android froze the process about 72 s after the tab opened, and a later redirect waited, with no error in the tab, until the process was unfrozen; with a foreground service, the process stayed at procState 4 and was never frozen. Ports 1455, 1456 and 3000 bound on both `127.0.0.1` and `::1`. A `startActivity` from the foreground service after the callback was allowed while the Custom Tab was in the app's task. So Kestrel offers browser sign-in for OpenRouter and the ChatGPT plan, runs every sign-in in a foreground service, binds only the loopback addresses that a redirect names, brings its settings back after the callback, and asks for `ACCESS_LOCAL_NETWORK` only for servers on the local network. `CustomTabSignInTest` repeats the redirect with a device's own Chrome when the instrumentation argument `kestrelRedirectCheck=true` is present.
- **The providers' session (plan 4C).** `scripts\a71-providers.ps1 -DryRun` ran every step on kestrel-api37 against the Copilot stand-in of the debug hook `provider-standin`, with the redirect case in the emulator's Chrome 145. The session with the owner is still open: it signs in to Copilot on kestrel-api37 and on the A71, runs every live case of `scripts\provider-check.ps1` on both, runs the providers' device tests and the redirect case with the A71's own Chrome, which repeats P5's q1 and q3, records whether the A71's credentials key takes the StrongBox path, and checks that the stored credential decrypts after a restart of Kestrel's process. Whether OpenAI accepts Kestrel's registration for ChatGPT plan use stays untested, because the owner has no Plus or Pro plan; if OpenAI refuses it in real use, the sign-in page says so and offers an OpenAI API key. The session's results go into this section and the README.

## 20. Risks and open questions
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```markdown
2. **Borrowed sign-in registrations.** Copilot, Codex, OpenRouter, Chutes and Claude sign-ins reuse OpenClaw's public client registrations, and the vendors can revoke them.
```

with:

```markdown
2. **Borrowed sign-in registrations.** The sign-ins of GitHub Copilot, xAI and MiniMax borrow another program's client registration or identity (GitHub's own Copilot client with VS Code's editor headers, the Grok CLI's client and proxy, and probably OpenClaw's MiniMax client), and the vendors can revoke them or object to their use. Each has its own switch on the Providers page with the links to its provider's terms, and only Copilot's is on by default: the owner chose this (option B) after reading what research found about each provider's terms. OpenRouter's browser sign-in needs no registration, and the ChatGPT plan uses OpenAI's own Sign in with ChatGPT for open-source apps. Chutes' sign-in, the Codex CLI's sign-ins and Anthropic's setup-token are not used.
```

- [ ] **Step 9: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 10: Commit**

```powershell
git add scripts/a71-providers.ps1 scripts/tests/A71Providers.Tests.ps1 README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "test(providers): add the owner's session script a71-providers.ps1, and the providers' results in the README and the spec" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Counts at the end of this plan

- `:core:providers` JVM: R₀ + 98 (Task 1 + 31, Task 2 + 20, Task 3 + 12, Task 4 + 17, Task 5 + 18).
- `:app` JVM: A₀ + 1 (Task 7: `SetupChecklistTest`).
- `:core:privacy` JVM: P₀ (unchanged).
- `:core:privacy` device tests: Q₀ + 6 (Task 4: `LoopbackServerTest`).
- The other device tests of `:app`: O₀ + 12 (Task 6: `ProviderEndToEndTest` 3 and `CustomTabSignInTest` 3, whose redirect case is skipped without `kestrelRedirectCheck=true`; Task 7: `ProvidersPageTest` 4, `SettingsAppTest` 1 and `MainActivityTest` 1).
- Pester: T₀ + 8 in each shell (Task 8: `ProviderCheck.Tests.ps1` 4; Task 9: `A71Providers.Tests.ps1` 4).

## Self-review

**Design coverage.** Task 1: D9's credential life, the refreshers and their four outcomes, `earliest_refresh_at` read leniently, the refresh in its own scope behind a mutex with the write before the release, `invalid_client` clearing the client ID, and D9's and D11's entries (the key's characters, HTTPS for a custom base URL, Google's key file, AWS keys, the model before "Check"); D15's cache. Task 2: D13's effective base URL through `ProfileHosts.baseUrl`, each style's key header, the ChatGPT plan's options, Azure's wire and header, Mantle, Bedrock and Vertex, the identity headers and xAI's proxy headers (and none for xAI's API key), and `ProfileClient` with D4's time limit and retries, D17's `QuotaOrPolicy` and `planLimitReached`, D11's local-network check, D14's price and spending, the switch and the wait for the allowlist. Task 3: D9's three device codes with their checks. Task 4: D10's loopback server and D9's ChatGPT plan with every case of D19's list, and OpenRouter. Task 5: D12's discovery, models, headers, allowance and targets, D13's account lists, offered rows and defaults; spec 10.5 and B27. Task 6: D10's service, Custom Tab and return, D11's permission, the graph with the pending credential and registration; spec 7.4, 10.4, 13.6 and 16.3. Task 7: D18's page, the ChatGPT plan's notices and states, the switches and terms, the checklist step; spec 13.1, 14.2 and 15. Task 8: D20's hooks, the stand-in and the check script. Task 9: D20's session and dry run, the README, and spec 18.4, 19, 19.1 and 20. Not covered here, by the design: Copilot's Gemini thought signature through Chat Completions, which the session's live cases check; a placeholder for it, if the session needs one, goes into plan 4B's `ChatCompletionsAdapter` in this plan's fix wave.

**Placeholder scan.** No step says "TBD", "add error handling" or "similar to Task N"; every code step holds the whole file or the exact old and new text. The starting counts R₀, A₀, P₀, Q₀, O₀ and T₀ are the controller's to fill in, as "Before you start" says.

**Type consistency.** The names follow the design's "Shared names", with the changes that "Changes to the design that this plan makes" lists. `SignInResult`, `SignInFailed`, `SignInFailure`, `TokenRefresher` and `Refresh` come from Task 1 and are used unchanged by Tasks 3, 4, 6 and 7; `ProviderTargets` and `ModelEntries` from Task 2 by Tasks 5 and 8; `LoopbackListener`, `LoopbackSession` and `LoopbackCallback` from Task 4 by Task 6's `LoopbackListenerAdapter`; `SignInState` and `CheckResult` from Task 6 by Task 7's `ProviderData`; `ProviderGraph.PROVIDERS_PAGE` equals `SettingsPage.PROVIDERS.name`. Plan 4B's names are used as the design fixes them: `Adapters.forStyle(style, http)`, `JsonActions(inner)`, `Discoveries.forProvider`, `SigV4Auth(keys, region, service, clock)`, `ServiceAccountAuth(key, http, clock)` with its default `tokens = ServiceAccountTokens.SHARED`, `MantleRouting.route(modelId, region)`, `VertexUrls.base(location, projectId)`, `validLocation` and `validProject`, `AzureRouting.styleFor(deployment)` and `normalise(endpoint)`.

**Counts.** See "Counts at the end of this plan".
