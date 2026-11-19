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
import androidx.compose.ui.platform.LocalResources
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
    // The provider and the choice that the add flow starts with, as after "Use an API key" on a profile's page.
    var addProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var addChoice by rememberSaveable { mutableStateOf<String?>(null) }
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
        ADD -> AddProfile(data, addProvider, addChoice, onSaved = {
            // The profile holds the credential now, so Kestrel forgets the sign-in's result (design D10).
            data.leaveAddFlow()
            view = LIST
        }) {
            data.leaveAddFlow()
            view = LIST
        }
        PROFILE -> ProfilePage(
            data,
            profileId.orEmpty(),
            onDeleted = { confirmed ->
                if (!confirmed) notice = ProviderTexts.SIGN_OUT_UNCONFIRMED
                view = LIST
            },
            // "Use an API key" after a failed "Sign in again": the add flow with the provider's key choice (design D9).
            onUseKey = { provider, choice ->
                data.leaveAddFlow()
                addProvider = provider
                addChoice = choice
                view = ADD
            },
        ) { view = LIST }
        else -> ProfileList(
            data,
            notice,
            onAdd = {
                notice = null
                // A new add flow always signs in again.
                data.leaveAddFlow()
                addProvider = null
                addChoice = null
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
private fun AddProfile(data: ProviderData, startProvider: String?, startChoice: String?, onSaved: () -> Unit, onBack: () -> Unit) {
    var providerId by rememberSaveable { mutableStateOf(startProvider) }
    var choiceId by rememberSaveable { mutableStateOf(startChoice) }
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
    // Strings through LocalResources, which follows a change of the configuration (lint's LocalContextGetResourceValueCall).
    val resources = LocalResources.current
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
                problem = if (keyFile == null) resources.getString(R.string.providers_key_file_bad) else null
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
            keyFile?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(resources.getString(R.string.providers_key_file_bad)))
        } else {
            Entries.awsKeys(accessKeyId, secret, session)?.let { Result.success(it) }
                ?: Result.failure(IllegalArgumentException(ProviderTexts.KEY_CHARACTERS))
        }
        AuthMethod.DEVICE_CODE, AuthMethod.BROWSER -> signedIn?.credential?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException(resources.getString(R.string.providers_waiting)))
    }

    fun check() {
        problem = null
        val typed = entered().getOrElse {
            problem = it.message
            return
        }
        val profile = draft()
        if (choice.method == AuthMethod.SERVER_ADDRESS && profile.baseUrl == null) {
            problem = resources.getString(R.string.providers_server_bad)
            return
        }
        if (choice.customBaseUrl && baseUrl.isBlank()) {
            problem = resources.getString(R.string.providers_base_url_missing)
            return
        }
        if (choice.customBaseUrl && profile.baseUrl == null) {
            problem = resources.getString(R.string.providers_base_url_bad)
            return
        }
        // The location becomes part of the host (plan 4B), and Vertex's check exchanges only the key.
        if (provider.id == VERTEX && !VertexUrls.validLocation(region)) {
            problem = resources.getString(R.string.providers_location_bad)
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

    // A sign-in of this add flow that ended: its credential goes on to the check. A profile's "Sign in again" is not one.
    val state by data.signIn.collectAsState()
    LaunchedEffect(state) {
        val done = state as? SignInState.Succeeded ?: return@LaunchedEffect
        if (done.profileId == null && done.providerId == provider.id && done.choiceId == choice.choiceId && signedIn == null) {
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
    profileId: String? = null,
) {
    val state by data.signIn.collectAsState()
    val setting by data.setting.collectAsState(initial = null)
    val context = LocalContext.current
    val activity = LocalActivity.current
    // The add flow shows only its own sign-in, and a profile's page only its own "Sign in again".
    val running = state.takeIf { it.matches(provider.id, choice.choiceId) && it.profileId == profileId }
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
                Button(onClick = { data.startSignIn(provider.id, choice.choiceId, consent = true, profileId = profileId) }) {
                    Text(stringResource(R.string.providers_allow_plan))
                }
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
    // One save per add flow: a second tap while the first save runs, or after it, would store the credential twice.
    var saving by remember { mutableStateOf(false) }
    Button(enabled = !saving, onClick = {
        saving = true
        scope.launch {
            var saved = false
            try {
                attempt(provider.label, { problem = it }) {
                    val draft = checked.draft.copy(name = name.ifBlank { provider.label }, lightweight = ProfileModel(light), versatile = ProfileModel(versatile))
                    data.save(draft, credential, checked.discovered, makeDefault)
                    saved = true
                    if (firstPlan) planNotice = true else onSaved()
                }
            } finally {
                // After a failed save the owner can try again.
                if (!saved) saving = false
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
private fun ProfilePage(data: ProviderData, profileId: String, onDeleted: (Boolean) -> Unit, onUseKey: (String, String) -> Unit, onBack: () -> Unit) {
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
    val profile = setting?.profiles?.firstOrNull { it.id == profileId }
    // Read again after "Sign in again", which stores its credential in the profile itself (design D10).
    LaunchedEffect(profileId, profile == null, profile?.signedInAt) {
        val shown = profile ?: return@LaunchedEffect
        attempt(data.catalog.provider(shown.providerId)?.label ?: shown.name, { problem = it }) {
            rows = data.models(profileId)
            masked = data.maskedKey(profileId)
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
                scope.launch { attempt(label, { problem = it }) { data.signInAgain(profile.id) } }
                Unit
            }
            Button(onClick = again) { Text(stringResource(R.string.providers_sign_in_again)) }
            // The page's own "Sign in again", also one that runs or failed while the owner was on another page.
            if (signIn.profileId == profile.id && provider != null && choice != null && signIn !is SignInState.Succeeded) {
                SignInSection(data, provider, choice, "", {}, { keyChoice -> onUseKey(provider.id, keyChoice) }, retry = again, profileId = profile.id)
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
