package dev.kestrel.app.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import dev.kestrel.agent.port.PermissionEnd

/**
 * Kestrel's own permission request during a task (design D12 and D15 of sub-project 5): a transparent activity that only
 * shows Android's dialog for the permissions of [EXTRA_PERMISSIONS], asked together, and then finishes with
 * finishAndRemoveTask(), so that the task's app comes back. It has no noHistory, because such an activity never gets the
 * result of what it starts (review A2-I5). It ends its request in [PermissionRequests] when Android's answer comes and
 * when it is destroyed for any other reason, except a configuration change, after which Android gives the pending answer
 * to the new instance (review A3-N4). When its request ends without an answer ("Kestrel, continue", the hand-over's 3
 * minutes, "stop"), it finishes the same way, so that Android's dialog does not stay open in a hidden task. The task reads
 * the permissions again itself, so the answer's content is not passed.
 */
class PermissionActivity : ComponentActivity() {
    private var request = 0L

    private val dialog = registerForActivityResult(RequestMultiplePermissions()) { end() }

    private val finish: () -> Unit = { runOnUiThread { finishAndRemoveTask() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = intent.getLongExtra(EXTRA_REQUEST, 0L)
        // Before the check below, so that a request that stops waiting in between still finishes this activity.
        PermissionRequests.register(request, finish)
        // An intent from an earlier task or process, as when the recent apps restore it, shows no dialog.
        if (!PermissionRequests.waits(request)) {
            end()
            return
        }
        // After a configuration change, Android gives the dialog's answer to this instance; asking again would show it twice.
        if (savedInstanceState != null) return
        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: emptyArray()
        if (permissions.isEmpty()) {
            end()
            return
        }
        dialog.launch(permissions)
    }

    override fun onDestroy() {
        PermissionRequests.unregister(request, finish)
        if (!isChangingConfigurations) PermissionRequests.ended(request)
        super.onDestroy()
    }

    private fun end() {
        PermissionRequests.ended(request)
        finishAndRemoveTask()
    }

    companion object {
        const val EXTRA_PERMISSIONS = "dev.kestrel.app.extra.PERMISSIONS"
        const val EXTRA_REQUEST = "dev.kestrel.app.extra.PERMISSION_REQUEST"

        /**
         * Asks Android for [permissions] through this activity and returns what Android granted afterwards (the
         * `askAndroid` of AppOwnerPort, which keeps the task handed over meanwhile). Kestrel's accessibility service lets
         * it start the activity from the background.
         */
        suspend fun ask(context: Context, permissions: List<String>): PermissionEnd = PermissionRequests.ask(
            start = { id ->
                try {
                    context.startActivity(intent(context, permissions, id))
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            },
            granted = { Permissions.granted(context, permissions) },
        )

        // The identifier makes each request's intent distinct, and FLAG_ACTIVITY_MULTIPLE_TASK gives it a task of its
        // own, so that Android never brings an earlier request's task to the front in place of starting this one.
        fun intent(context: Context, permissions: List<String>, request: Long): Intent =
            Intent(context, PermissionActivity::class.java)
                .setIdentifier("request-$request")
                .putExtra(EXTRA_PERMISSIONS, permissions.toTypedArray())
                .putExtra(EXTRA_REQUEST, request)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
