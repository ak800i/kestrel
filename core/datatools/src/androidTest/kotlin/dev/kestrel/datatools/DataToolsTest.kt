package dev.kestrel.datatools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.datatools.contacts.AndroidContacts
import dev.kestrel.datatools.contacts.FindContact
import dev.kestrel.datatools.media.MediaTool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

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

    @Test
    fun findMediaFindsTheTestPhotosOfTheirDayAndBuildsTheirShareIntent() {
        addTestPhoto(1, DAY.atTime(12, 0))
        addTestPhoto(2, DAY.atTime(12, 5))
        val media = MediaTool(resolver, { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })

        val found = waitFor { (runBlocking { media.find(DAY, DAY, MediaKinds.PHOTOS, ZoneId.systemDefault()) } as DataResult.Ok).takeIf { it.lines.size == 2 } }

        assertEquals(listOf("m1 · photo · 2001-02-03 12:00", "m2 · photo · 2001-02-03 12:05"), found.lines.map { it.fixed.substringBeforeLast(" · ") })
        assertEquals(listOf("m3"), media.unknown(listOf("m1", "m3")))
        val share = media.shareIntent("org.telegram.messenger", listOf("m1", "m2"))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action)
        assertEquals("org.telegram.messenger", share.`package`)
        assertEquals("image/*", share.type)
        assertEquals(2, share.clipData?.itemCount)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    /**
     * Adds a photo of this test package to MediaStore in [PHOTOS_FOLDER], with EXIF's `DateTimeOriginal` and
     * `OffsetTimeOriginal` of [taken] in the device's zone, from which Android fills `DATE_TAKEN` when the photo is
     * published (research r65).
     */
    private fun addTestPhoto(number: Int, taken: LocalDateTime) {
        val name = "kestrel_test_$number.jpg"
        val file = File(context.cacheDir, name)
        val card = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { card.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val offset = ZoneId.systemDefault().rules.getOffset(taken).totalSeconds
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, taken.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")))
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "%s%02d:%02d".format(if (offset < 0) "-" else "+", abs(offset) / 3600, abs(offset) % 3600 / 60))
            saveAttributes()
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTOS_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        file.delete()
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

    /** Removes everything that the cases add: the contacts of the test account and this package's test photos. */
    private fun removeTestData() {
        val rawContacts = RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        resolver.delete(rawContacts, "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ?", arrayOf(TEST_ACCOUNT, TEST_ACCOUNT_TYPE))
        resolver.delete(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf("$PHOTOS_FOLDER/", context.packageName),
        )
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
        val PERMISSIONS = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )

        // A day long ago, on which no other photo of the emulator was taken, and a folder of this test's own.
        val DAY: LocalDate = LocalDate.of(2001, 2, 3)
        const val PHOTOS_FOLDER = "Pictures/KestrelDataTools"

        // The account of the instant commands' test contacts (PhoneTests), which command-test.ps1 also deletes.
        const val TEST_ACCOUNT = "kestrel-test"
        const val TEST_ACCOUNT_TYPE = "dev.kestrel.test"

        // The fictional number of design D28.
        const val NUMBER = "+1 202 555 0143"
        const val TELEGRAM = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile"
    }
}
