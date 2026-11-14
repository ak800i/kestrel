package dev.kestrel.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SetupAction
import dev.kestrel.app.settings.SetupStatus
import dev.kestrel.app.settings.SetupStatusReader

/** Kestrel's settings app (design spec, section 15). */
class MainActivity : ComponentActivity() {
    private var status by mutableStateOf<SetupStatus?>(null)

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
                status?.let { SettingsApp(it, ::perform) }
            }
        }
    }

    // The owner may have changed a setting in another app, such as Android's accessibility settings.
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status = SetupStatusReader(this).read()
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

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
