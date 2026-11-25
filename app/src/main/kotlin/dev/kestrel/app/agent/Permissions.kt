package dev.kestrel.app.agent

import android.content.Context
import android.content.pm.PackageManager
import dev.kestrel.agent.port.PermissionEnd

/** Kestrel's own permissions as Android holds them now (design D12 of sub-project 5). */
internal object Permissions {
    fun granted(context: Context, permissions: List<String>): PermissionEnd {
        val held = permissions.count { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return when (held) {
            permissions.size -> PermissionEnd.GRANTED
            0 -> PermissionEnd.REFUSED
            else -> PermissionEnd.PARTLY
        }
    }
}
