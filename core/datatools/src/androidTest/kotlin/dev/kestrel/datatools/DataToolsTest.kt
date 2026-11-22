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
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
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
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.datatools.calendar.CalendarTool
import dev.kestrel.datatools.clock.ClockIntents
import dev.kestrel.datatools.contacts.AndroidContacts
import dev.kestrel.datatools.contacts.FindContact
import dev.kestrel.datatools.media.MediaTool
import dev.kestrel.datatools.navigation.NavigationIntents
import dev.kestrel.datatools.sms.SmsTool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * The data tools against Android's own providers (design D12 and D27 of sub-project 5): each case adds its own test data
 * under this test package and removes it again, also after a failure. `AndroidContacts.details()` reads every contact on
 * the device, which on kestrel-api37 holds only test data, so on any other device [onlyOnKestrelApi37] skips every case
 * before it touches anything. The intents are built and checked, never started.
 */
@RunWith(AndroidJUnit4::class)
class DataToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // A library's device tests run in their own test package, which is also the target here.
    private val context: Context = instrumentation.targetContext
    private val resolver: ContentResolver = context.contentResolver

    // A rule runs around @Before and @After, so a skipped case neither grants nor deletes anything.
    @get:Rule
    val onlyOnKestrelApi37 = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Runs only on kestrel-api37", avdName() == "kestrel-api37")
                base.evaluate()
            }
        }
    }

    private fun avdName(): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name"))
            .use { it.readBytes().decodeToString().trim() }

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

    @Test
    fun listEventsReadsTheEventOfATestCalendar() {
        val zone = ZoneId.systemDefault()
        val calendar = addTestCalendar()
        val start = DAY.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        resolver.insert(
            CalendarContract.Events.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar)
                put(CalendarContract.Events.TITLE, "Kestrel test lunch")
                put(CalendarContract.Events.EVENT_LOCATION, "Test Street 1")
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + 3_600_000)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            },
        )
        val tool = CalendarTool(resolver, { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })

        val line = waitFor { (runBlocking { tool.list(DAY, DAY, zone) } as DataResult.Ok).lines.singleOrNull() }

        assertEquals("event · 2001-02-03 12:00 to 13:00", line.fixed)
        assertEquals(listOf("title" to "Kestrel test lunch", "location" to "Test Street 1", "calendar" to TEST_CALENDAR), line.fields.map { it.name to it.value })
    }

    @Test
    fun theIntentsOfTheClockTheSmsAppAndTheCalendarCarryTheirExtras() {
        val alarm = ClockIntents.alarm(7, 30, "Kestrel test", listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarm.action)
        assertEquals(7, alarm.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, alarm.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertTrue(alarm.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertEquals(arrayListOf(2, 6), alarm.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))

        val timer = ClockIntents.timer(90, null)
        assertEquals(AlarmClock.ACTION_SET_TIMER, timer.action)
        assertEquals(90, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertTrue(timer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))

        val sms = SmsTool.composeIntent(NUMBER, "I'm late")
        assertEquals(Intent.ACTION_SENDTO, sms.action)
        assertEquals("smsto", sms.data?.scheme)
        assertEquals(NUMBER, sms.data?.schemeSpecificPart)
        assertEquals("I'm late", sms.getStringExtra("sms_body"))

        val zone = ZoneId.systemDefault()
        val event = CalendarTool.insertIntent("Kestrel test", DAY.atTime(9, 0), null, null, zone)
        assertEquals(Intent.ACTION_INSERT, event.action)
        val begin = DAY.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(begin, event.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertEquals(begin + 3_600_000, event.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
        assertEquals("Kestrel test", event.getStringExtra(CalendarContract.Events.TITLE))
    }

    @Test
    fun anIntentSpecBecomesTheIntentThatItDescribes() {
        val link = IntentSpecs.toIntent(IntentSpec(Intent.ACTION_VIEW, "https://en.wikipedia.org/", "org.mozilla.firefox", browsable = true, extras = mapOf("note" to "test")))
        assertEquals(Intent.ACTION_VIEW, link.action)
        assertEquals("https://en.wikipedia.org/", link.dataString)
        assertEquals("org.mozilla.firefox", link.`package`)
        assertTrue(link.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("test", link.getStringExtra("note"))
        assertTrue(link.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val waze = IntentSpecs.toIntent(NavigationIntents.waze("Main Street 1"))
        assertEquals("waze://?q=Main%20Street%201&navigate=yes", waze.dataString)
        assertEquals(NavigationIntents.WAZE, waze.`package`)
        assertFalse(waze.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** Adds a local calendar of the test account, as a sync adapter may, and returns its ID. */
    private fun addTestCalendar(): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, TEST_CALENDAR)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, TEST_CALENDAR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, TEST_ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        return checkNotNull(resolver.insert(testCalendars(), values)).lastPathSegment!!.toLong()
    }

    private fun testCalendars() = CalendarContract.Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

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

    /** Removes everything that the cases add: the contacts and the calendar of the test account and this package's test photos. */
    private fun removeTestData() {
        // Deleting a calendar deletes its events.
        resolver.delete(testCalendars(), "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?", arrayOf(TEST_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL))
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
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
        const val TEST_CALENDAR = "Kestrel test calendar"

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
