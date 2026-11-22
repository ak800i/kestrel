package dev.kestrel.datatools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.port.DataResult
import dev.kestrel.datatools.contacts.AndroidContacts
import dev.kestrel.datatools.contacts.FindContact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data tools against Android's own providers on kestrel-api37 (design D12 and D27 of sub-project 5): each case adds
 * its own test data under this test package and removes it again, also after a failure, and never reads anything else.
 * The intents are built and checked, never started.
 */
@RunWith(AndroidJUnit4::class)
class DataToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // A library's device tests run in their own test package, which is also the target here.
    private val context: Context = instrumentation.targetContext
    private val resolver: ContentResolver = context.contentResolver

    @Before
    fun grant() {
        for (permission in PERMISSIONS) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        removeTestData()
    }

    @After
    fun cleanUp() = removeTestData()

    @Test
    fun findContactGivesTheTestContactsNumberAddressAndMessagingApps() {
        addTestContact("Zora Testić", phone = NUMBER, email = "zora@example.com", appMimetype = TELEGRAM)

        val line = waitFor { (FindContact.result("Zora Testić", AndroidContacts(resolver).details()) as DataResult.Ok).lines.singleOrNull() }

        assertEquals("contact 1 (apps: Telegram)", line.fixed)
        assertEquals(listOf("name", "phone", "e-mail"), line.fields.map { it.name })
        assertEquals("$NUMBER (mobile)", line.fields[1].value)
    }

    /** Adds a contact of the test account with a name and, when given, a mobile number, an address and an app's row. */
    private fun addTestContact(name: String, phone: String? = null, email: String? = null, appMimetype: String? = null) {
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, TEST_ACCOUNT_TYPE)
                .withValue(RawContacts.ACCOUNT_NAME, TEST_ACCOUNT)
                .build(),
            data(StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME, name),
        )
        phone?.let { operations += data(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, it, Phone.TYPE to Phone.TYPE_MOBILE) }
        email?.let { operations += data(Email.CONTENT_ITEM_TYPE, Email.ADDRESS, it) }
        appMimetype?.let { operations += data(it, Data.DATA1, "1000001") }
        resolver.applyBatch(ContactsContract.AUTHORITY, operations)
    }

    private fun data(mimetype: String, column: String, value: String, type: Pair<String, Int>? = null): ContentProviderOperation =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, mimetype)
            .withValue(column, value)
            .apply { type?.let { withValue(it.first, it.second) } }
            .build()

    /** Removes everything that the cases add: the contacts of the test account. */
    private fun removeTestData() {
        val rawContacts = RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        resolver.delete(rawContacts, "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ?", arrayOf(TEST_ACCOUNT, TEST_ACCOUNT_TYPE))
    }

    /** The provider joins a new raw contact into a contact shortly after the insert, so the cases wait up to 5 s for it. */
    private fun <T : Any> waitFor(read: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            read()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "The test data did not appear within 5 s" }
            Thread.sleep(100)
        }
    }

    private companion object {
        val PERMISSIONS = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

        // The account of the instant commands' test contacts (PhoneTests), which command-test.ps1 also deletes.
        const val TEST_ACCOUNT = "kestrel-test"
        const val TEST_ACCOUNT_TYPE = "dev.kestrel.test"

        // The fictional number of design D28.
        const val NUMBER = "+1 202 555 0143"
        const val TELEGRAM = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile"
    }
}
