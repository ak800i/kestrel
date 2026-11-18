package dev.kestrel.app.commands

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether Kestrel may read the contacts (`READ_CONTACTS`, design spec, section 13.6). An interface, so that the JVM tests
 * can check the first use without the permission.
 */
internal fun interface ContactsAccess {
    fun allowed(): Boolean
}

/** Kestrel's own permission to read the contacts, which the owner gives at the first "call [name]". */
internal fun contactsAccess(context: Context) = ContactsAccess {
    context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
}
