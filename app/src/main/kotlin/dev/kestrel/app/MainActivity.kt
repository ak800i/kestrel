package dev.kestrel.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.kestrel.app.providers.AndroidLocalNetworkAccess
import dev.kestrel.app.settings.ModelCheck
import dev.kestrel.app.settings.PageRequest
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.app.settings.SetupAction
import dev.kestrel.app.settings.SetupStatus
import dev.kestrel.app.settings.SetupStatusReader
import dev.kestrel.app.settings.StoredSettingsData
import dev.kestrel.app.settings.checkModelFiles
import dev.kestrel.app.settings.writeLogExport
import dev.kestrel.datatools.notifications.KestrelNotificationListener
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** Kestrel's settings app (design spec, section 15). */
class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as KestrelApplication).graph }
    private val data by lazy { StoredSettingsData(graph, applicationContext) }
    private var status by mutableStateOf<SetupStatus?>(null)

    /** The page that the latest intent asked for, such as the list of commands after "What can I say?". */
    private var pageRequest by mutableStateOf<PageRequest?>(null)

    private val exportLogs = registerForActivityResult(CreateDocument("application/json")) { uri -> uri?.let(::export) }

    /** The permissions that were missing when the request started, and for which Android would show no explanation. */
    private var missingWithoutRationale = emptySet<String>()

    // After two refusals Android no longer shows its dialog. If a missing permission had no explanation both before and
    // after the request, Android showed no dialog for it, and the owner allows it in the app's settings instead.
    private val permissions = registerForActivityResult(RequestMultiplePermissions()) { results ->
        refresh()
        val noDialog = results.any { (permission, granted) ->
            !granted && permission in missingWithoutRationale && !shouldShowRequestPermissionRationale(permission)
        }
        if (noDialog) openAppSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After the activity is made again, as after a rotation, the page that the owner has open stays. Reopened from the
        // recent apps, Android gives it the task's first intent again, which may be an old "What can I say?".
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) follow(intent)
        setContent {
            MaterialTheme {
                status?.let {
                    SettingsApp(it, data, ::perform, ::checkModels, pageRequest) {
                        exportLogs.launch("kestrel-logs-${LocalDate.now()}.json")
                    }
                }
            }
        }
    }

    // MainActivity is singleTop, so an intent for it while it is in front comes here, such as a second "What can I say?".
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        follow(intent)
    }

    /**
     * Shows the page that [EXTRA_PAGE] names, and asks for the permission of [EXTRA_ASK_PERMISSION] when [ASKABLE] has it.
     * Any app may start MainActivity, so an unknown page or any other permission is ignored.
     */
    private fun follow(intent: Intent) {
        intent.getStringExtra(EXTRA_PAGE)
            ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
            ?.let { pageRequest = PageRequest(it, resumeAdd = intent.getBooleanExtra(EXTRA_RESUME_ADD, false)) }
        intent.getStringExtra(EXTRA_ASK_PERMISSION)?.takeIf { it in ASKABLE }?.let { askForPermissions(it) }
    }

    // The owner may have changed a setting in another app, such as Android's accessibility settings.
    override fun onResume() {
        super.onResume()
        // The A71 session reads the time of the settings in front (plan 2E).
        graph.timeline.settingsResumed()
        graph.problems.settingsOpened()
        refresh()
    }

    override fun onPause() {
        graph.timeline.settingsPaused()
        super.onPause()
    }

    // The A71 checks' clipboard hook reads the clipboard only while this window has the focus (plan 4D).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        graph.timeline.settingsFocusChanged(hasFocus)
    }

    // Whether READ_PHONE_STATE was granted at the last refresh. A grant in Android's settings does not restart Kestrel,
    // so the call monitor starts here too (section 8.7).
    private var phoneStateGranted = false

    private fun refresh() {
        // Checking the model files can take seconds the first time, so the other steps show at once, and the model
        // files keep their last known status until the check ends.
        status = SetupStatusReader(this).read().copy(models = status?.models ?: ModelCheck.Checking)
        // Kestrel watches the call state from now on, also while it listens already. refresh() runs in onResume and
        // after the permission dialog, so this covers a grant in the dialog and one in Android's settings.
        val granted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (granted && !phoneStateGranted) graph.listening.watchCalls()
        phoneStateGranted = granted
        checkModels()
    }

    /** The hash cache makes a check of unchanged files cost only a few stat calls, so pages may check often. */
    private fun checkModels() {
        // After a failed check, the pages say "Checking…" again until this one ends.
        status = status?.let { if (it.models == ModelCheck.Failed) it.copy(models = ModelCheck.Checking) else it }
        lifecycleScope.launch(graph.errors) {
            val coroutine = coroutineContext
            // A failure is logged by its classes only (section 13.5), and the pages say that the check failed.
            val models = checkModelFiles({ graph.models.check() }) { error -> graph.errors.handleException(coroutine, error) }
            status = status?.copy(models = models)
        }
    }

    private fun perform(action: SetupAction) {
        when (action) {
            SetupAction.ACCESSIBILITY_SETTINGS -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupAction.PERMISSIONS -> askForPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            // Section 8.7: the call state and the call commands.
            SetupAction.CALL_PERMISSIONS ->
                askForPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
            SetupAction.APP_SETTINGS -> openAppSettings()
            // Android does not let an app ask for the assistant role, so the owner picks Kestrel there (research r6).
            SetupAction.DEFAULT_APPS -> startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            // Design D26 of sub-project 5: Android's page for Kestrel's notification listener.
            SetupAction.NOTIFICATION_ACCESS -> startActivity(KestrelNotificationListener.accessSettings(this))
        }
    }

    private fun askForPermissions(vararg wanted: String) {
        missingWithoutRationale = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it) }
            .toSet()
        permissions.launch(arrayOf(*wanted))
    }

    /** Writes both logs to the file that the owner chose. The logs leave the phone only this way (design spec, 13.5). */
    private fun export(uri: Uri) {
        lifecycleScope.launch(graph.errors) {
            val written = writeLogExport(contentResolver, uri, graph.logs)
            val message = if (written) R.string.logs_exported else R.string.logs_export_failed
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    companion object {
        /** The name of a [SettingsPage] to show, such as the list of commands that "What can I say?" opens. */
        const val EXTRA_PAGE = "dev.kestrel.app.extra.PAGE"

        /** A permission of [ASKABLE] to ask for, as when Kestrel needs the contacts to call by name (design spec, 13.6). */
        const val EXTRA_ASK_PERMISSION = "dev.kestrel.app.extra.ASK_PERMISSION"

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

        /**
         * An intent that starts Kestrel's settings app from outside it, such as from the accessibility service or a
         * notification, which Android lets start activities. MainActivity is singleTop, so a settings app on top of its
         * task gets the intent. The intent gets an identifier of its own, so that it never equals the intent that started
         * Kestrel's task: when something covers the settings app in that task, such as Android's accessibility settings
         * that its checklist opened or a permission dialog that outlived Kestrel's process, Android then starts the
         * settings app on top of it, as it does when the launcher started the task, and closes nothing. With an equal
         * intent, Android only brings the task to the front, and the settings never show.
         */
        fun freshIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setIdentifier(UUID.randomUUID().toString())
    }
}
