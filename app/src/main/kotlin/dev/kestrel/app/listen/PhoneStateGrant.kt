package dev.kestrel.app.listen

/**
 * Starts listening's call watch once Kestrel holds READ_PHONE_STATE (section 8.7). A grant in Android's settings does not
 * restart Kestrel, so each screen of Kestrel's checks it as it comes to the front ([check]): the conversation screen, which
 * the launcher opens, and the settings app, also after its permission dialog (review B-M2 of plan 7F). [granted] reads the
 * permission, and [watchCalls] is `Listening.watchCalls`. Any thread may call it.
 */
internal class PhoneStateGrant(private val granted: () -> Boolean, private val watchCalls: () -> Unit) {
    // Whether the permission was granted at the last check.
    private var seen = false

    @Synchronized
    fun check() {
        val now = granted()
        if (now && !seen) watchCalls()
        seen = now
    }

    override fun toString(): String = "PhoneStateGrant(seen=$seen)"
}
