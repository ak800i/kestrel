package dev.kestrel.app.commands

import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.runBlocking

/**
 * Helpers of plan 3C's command device tests: Android's own apps, and the rows that the tests add to the contacts, the
 * call log and the media store, through the shell, which may change them. command-test.ps1 deletes what a test leaves.
 * Nothing here reads a row's content other than its ID.
 */
internal object PhoneTests {
    const val SYSTEM_UI = "com.android.systemui"
    const val CLOCK = "com.google.android.deskclock"
    const val SETTINGS = "com.android.settings"
    const val CALL_LOG = "content://call_log/calls"
    const val IMAGES = "content://media/external/images/media"

    /** The account of the contacts that the tests add; command-test.ps1 deletes its contacts at the end. */
    const val TEST_ACCOUNT = "kestrel-test"

    // Phone.TYPE_MOBILE and Phone.TYPE_WORK of Android's contacts.
    const val MOBILE = 2
    const val WORK = 3

    private val ID = Regex("_id=(\\d+)")

    /** The package of the home app, which also shows the recent apps. */
    fun homePackage(): String =
        VoiceTests.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .trim().lines().last().substringBefore('/')

    /** The package of the app window that is in front. */
    fun activeApp(session: ScreenSession): String? = runBlocking {
        session.snapshots.current().snapshot.windows.firstOrNull { it.kind == WindowKind.APPLICATION && it.active }?.packageName
    }

    /** Waits up to [timeoutMillis] until the app window of [packageName] is in front. */
    fun awaitActiveApp(session: ScreenSession, packageName: String, timeoutMillis: Long = 10_000): Boolean =
        VoiceTests.waitUntil(timeoutMillis) { activeApp(session) == packageName }

    /** The largest row ID of the content [uri], or 0. */
    fun largestRowId(uri: String): Long =
        ID.findAll(VoiceTests.shell("content query --uri $uri --projection _id")).maxOfOrNull { it.groupValues[1].toLong() } ?: 0

    /** Deletes the rows of [uri] whose ID is larger than [id]. */
    fun deleteRowsAfter(uri: String, id: Long) {
        VoiceTests.shell("content delete --uri $uri --where _id>$id")
    }

    /**
     * Adds a contact of [TEST_ACCOUNT] with a [given] and a [family] name, which Android joins into its display name, and
     * [numbers] with their types. The shell splits a command at spaces, so no value may have one.
     */
    fun addContact(given: String, family: String, vararg numbers: Pair<String, Int>) {
        VoiceTests.shell(
            "content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:dev.kestrel.test " +
                "--bind account_name:s:$TEST_ACCOUNT",
        )
        val rows = VoiceTests.shell(
            "content query --uri content://com.android.contacts/raw_contacts --projection _id --where account_name='$TEST_ACCOUNT'",
        )
        val id = ID.findAll(rows).maxOf { it.groupValues[1].toLong() }
        VoiceTests.shell(
            "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id " +
                "--bind mimetype:s:vnd.android.cursor.item/name --bind data2:s:$given --bind data3:s:$family",
        )
        for ((number, type) in numbers) {
            VoiceTests.shell(
                "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id " +
                    "--bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:$number --bind data2:i:$type",
            )
        }
    }

    /** Deletes every contact of [TEST_ACCOUNT], also those of an interrupted earlier run. */
    fun removeTestContacts() {
        VoiceTests.shell(
            "content delete --uri content://com.android.contacts/raw_contacts?caller_is_syncadapter=true --where account_name='$TEST_ACCOUNT'",
        )
    }
}
