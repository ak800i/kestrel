package dev.kestrel.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import dev.kestrel.app.settings.ModelCheck
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SetupAction
import dev.kestrel.app.settings.SetupStatus
import dev.kestrel.app.settings.SetupStatusReader
import dev.kestrel.app.settings.StoredSettingsData
import dev.kestrel.app.settings.checkModelFiles
import dev.kestrel.app.settings.writeLogExport
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Kestrel's settings app (design spec, section 15). */
class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as KestrelApplication).graph }
    private val data by lazy { StoredSettingsData(graph, applicationContext) }
    private var status by mutableStateOf<SetupStatus?>(null)

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
        setContent {
            MaterialTheme {
                status?.let {
                    SettingsApp(it, data, ::perform, ::checkModels) {
                        exportLogs.launch("kestrel-logs-${LocalDate.now()}.json")
                    }
                }
            }
        }
    }

    // The owner may have changed a setting in another app, such as Android's accessibility settings.
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        // Checking the model files can take seconds the first time, so the other steps show at once, and the model
        // files keep their last known status until the check ends.
        status = SetupStatusReader(this).read().copy(models = status?.models ?: ModelCheck.Checking)
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
            SetupAction.PERMISSIONS -> requestPermissions()
            SetupAction.APP_SETTINGS -> openAppSettings()
        }
    }

    private fun requestPermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        missingWithoutRationale = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it) }
            .toSet()
        permissions.launch(wanted)
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
}
