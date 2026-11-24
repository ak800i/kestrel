package dev.kestrel.datatools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import dev.kestrel.agent.port.AccessKind
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.MediaAction
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.agent.port.NavigationApp
import dev.kestrel.agent.port.TravelMode
import dev.kestrel.commands.phone.Contact
import dev.kestrel.datatools.calendar.CalendarTool
import dev.kestrel.datatools.calls.CallLogTool
import dev.kestrel.datatools.clock.ClockIntents
import dev.kestrel.datatools.contacts.AndroidContacts
import dev.kestrel.datatools.contacts.ContactsWriter
import dev.kestrel.datatools.contacts.FindContact
import dev.kestrel.datatools.contacts.contactsAccess
import dev.kestrel.datatools.location.GpsLocation
import dev.kestrel.datatools.media.MediaKeys
import dev.kestrel.datatools.media.MediaQuery
import dev.kestrel.datatools.media.MediaTool
import dev.kestrel.datatools.navigation.NavigationIntents
import dev.kestrel.datatools.notifications.KestrelNotificationListener
import dev.kestrel.datatools.notifications.NotificationLines
import dev.kestrel.datatools.sms.SmsTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The agent's data tools on Android (design D9 and D12 of sub-project 5): plan 5A's [DataPort] over Android's providers,
 * its location, its notification listener and the apps' intents. A tool that opens an app's screen only describes the
 * intent ([DataResult.Opens]): the port has no way to start an activity, so the agent's launch check opens it, as it opens
 * every app for the agent (plan 6B-basefix). [isAgentFree] is the task's list of agent-free apps, which
 * `list_notifications` leaves out. A missing permission gives
 * [DataResult.NeedsPermission] with the permissions that the tool asks for together, and the agent asks the owner (D12);
 * so does a permission that Android takes back during a read. Nothing here logs, and nothing starts a coroutine of its own.
 */
class AndroidDataPort(
    context: Context,
    private val isAgentFree: (String?) -> Boolean,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : DataPort {
    private val app = context.applicationContext ?: context
    private val resolver = app.contentResolver
    private val granted: (String) -> Boolean = { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private val contactsAllowed = contactsAccess(app)
    private val contacts = AndroidContacts(resolver)
    private val writer = ContactsWriter(resolver)
    private val media = MediaTool(resolver, granted)
    private val mediaCalls = AfterCalls(media::forget)
    private val calendar = CalendarTool(resolver, granted)
    private val sms = SmsTool(resolver, granted) { if (contactsAllowed.allowed()) smsContacts() else null }
    private val calls = CallLogTool(resolver, granted)
    private val location = GpsLocation(app.getSystemService(LocationManager::class.java), granted)
    private val keys = MediaKeys(app.getSystemService(AudioManager::class.java))

    override suspend fun findContact(name: String): DataResult {
        if (!contactsAllowed.allowed()) return DataResult.NeedsPermission(CONTACTS)
        return DataPortRules.guarded(CONTACTS) { FindContact.result(name, withContext(Dispatchers.IO) { contacts.details() }) }
    }

    override suspend fun findMedia(from: LocalDate, until: LocalDate, kinds: MediaKinds): DataResult = mediaCalls.call {
        DataPortRules.guarded(MediaQuery.permissions(kinds, Build.VERSION.SDK_INT)) { media.find(from, until, kinds, zone()) }
    }

    override suspend fun shareMedia(packageName: String, items: List<String>): DataResult = mediaCalls.call {
        DataPortRules.shareRefusal(items, media.unknown(items))
            ?: DataResult.Opens(media.shareIntent(packageName, items), "Kestrel opened the share screen with ${items.distinct().size} items; choose the chat on the screen.")
    }

    override suspend fun listEvents(from: LocalDate, to: LocalDate): DataResult =
        DataPortRules.guarded(listOf(Manifest.permission.READ_CALENDAR)) { calendar.list(from, to, zone()) }

    override suspend fun createEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?): DataResult =
        DataResult.Opens(CalendarTool.insertIntent(title, start, end, location, zone()), "Kestrel opened the calendar's new event; tap Save to keep it.")

    override suspend fun setAlarm(hour: Int, minute: Int, label: String?, days: List<DayOfWeek>): DataResult =
        DataResult.Opens(ClockIntents.alarm(hour, minute, label, days), "Kestrel asked the clock app to set an alarm for %02d:%02d.".format(hour, minute), front = false)

    override suspend fun setTimer(seconds: Int, label: String?): DataResult =
        DataResult.Opens(ClockIntents.timer(seconds, label), "Kestrel asked the clock app to set a timer of $seconds s.", front = false)

    override suspend fun listNotifications(): DataResult = DataPortRules.guarded(emptyList()) {
        if (!KestrelNotificationListener.hasAccess(app)) return@guarded DataResult.NeedsAccess(AccessKind.NOTIFICATIONS)
        withTimeoutOrNull(CONNECT_MILLIS) { KestrelNotificationListener.connected.first { it } }
        val listener = KestrelNotificationListener.instance
            ?: return@guarded DataResult.Failed("Android has not connected Kestrel's notification listener yet")
        val facts = withContext(Dispatchers.IO) { listener.facts() }
        DataResult.Ok(NotificationLines.lines(facts, app.packageName, isAgentFree, Build.VERSION.SDK_INT, zone()))
    }

    override suspend fun listSms(from: String?, limit: Int): DataResult =
        DataPortRules.guarded(listOf(Manifest.permission.READ_SMS)) { sms.list(from, limit, zone()) }

    override suspend fun composeSms(number: String, text: String): DataResult =
        DataResult.Opens(SmsTool.composeIntent(number, text), "Kestrel opened a new SMS with the text; it is not sent.")

    override suspend fun listCalls(limit: Int): DataResult =
        DataPortRules.guarded(listOf(Manifest.permission.READ_CALL_LOG)) { calls.list(limit, zone()) }

    // GpsLocation makes binder calls on its caller's thread.
    override suspend fun getLocation(): DataResult =
        DataPortRules.guarded(LOCATION) { withContext(Dispatchers.IO) { location.current() } }

    override suspend fun mediaControl(action: MediaAction): DataResult = keys.press(action)

    override suspend fun navigate(app: NavigationApp, to: String, mode: TravelMode?): DataResult {
        if (app == NavigationApp.WAZE && installed(NavigationIntents.WAZE)) return DataResult.Opens(NavigationIntents.waze(to), NavigationIntents.WAZE_RESULT)
        val spec = NavigationIntents.googleMaps(to, mode) ?: return DataResult.Failed(NavigationIntents.MAPS_NEEDS_ADDRESS)
        if (!installed(NavigationIntents.GOOGLE_MAPS)) return DataResult.Failed("neither Waze nor Google Maps is installed")
        val fellBack = if (app == NavigationApp.WAZE) "Waze is not installed, so Kestrel used Google Maps. " else ""
        return DataResult.Opens(spec, fellBack + NavigationIntents.MAPS_RESULT)
    }

    // add_contact and delete_contact ask for both contacts permissions together (design D19 of sub-project 6).
    override suspend fun addContact(name: String, number: String): DataResult {
        if (!CONTACT_TOOLS.all(granted)) return DataResult.NeedsPermission(CONTACT_TOOLS)
        return DataPortRules.guarded(CONTACT_TOOLS) {
            withContext(Dispatchers.IO) {
                val fields = listOf(DataField("name", name), DataField("number", number))
                if (ContactsWriter.duplicate(name, number, contacts.read())) {
                    DataResult.Ok(listOf(DataLine(DataPort.ADDED_NOTHING, fields)))
                } else {
                    writer.add(name, number) ?: return@withContext DataResult.Failed("Android did not add the contact")
                    DataResult.Ok(listOf(DataLine("added contact", fields)))
                }
            }
        }
    }

    override suspend fun deleteContact(ref: String): DataResult {
        if (!CONTACT_TOOLS.all(granted)) return DataResult.NeedsPermission(CONTACT_TOOLS)
        val id = ref.toLongOrNull() ?: return DataResult.Failed("Kestrel has no such contact")
        return DataPortRules.guarded(CONTACT_TOOLS) {
            if (withContext(Dispatchers.IO) { writer.delete(id) }) {
                DataResult.Ok(listOf(DataLine("deleted the contact", emptyList())))
            } else {
                DataResult.Failed("the contact is no longer there")
            }
        }
    }

    /** Drops the media table once no `find_media` or `share_media` runs, so after the task's coroutine has stopped. */
    override fun forgetTask() = mediaCalls.request()

    /** The contacts for `list_sms`' names, or null when Android takes the permission back during the read. */
    private fun smsContacts(): List<Contact>? = try {
        contacts.read()
    } catch (e: SecurityException) {
        null
    }


    private fun installed(packageName: String): Boolean = app.packageManager.getLaunchIntentForPackage(packageName) != null

    override fun toString(): String = "AndroidDataPort($media)"

    private companion object {

        // Android binds the listener shortly after access is given; a read waits at most this long for it.
        const val CONNECT_MILLIS = 2_000L

        val CONTACTS = listOf(Manifest.permission.READ_CONTACTS)
        val CONTACT_TOOLS = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

/** The rules of [AndroidDataPort] that need no Android, for the JVM tests. */
internal object DataPortRules {
    const val NO_ITEMS = "share_media takes only item numbers that find_media gave in this task; use find_media first"
    const val REFUSED = "Android did not let Kestrel read this"

    /**
     * Why `share_media` cannot share [items], of which [unknown] are numbers that this task's `find_media` never gave; null
     * when it can. The sentence is fixed and holds no item, because the items come from the model.
     */
    fun shareRefusal(items: List<String>, unknown: List<String>): DataResult.Failed? =
        if (items.isEmpty() || unknown.isNotEmpty()) DataResult.Failed(NO_ITEMS) else null

    /**
     * Runs [read], and turns Android's refusal during the read ([SecurityException], when the owner takes a permission back
     * after the check) into the tool's [permissions], or into [REFUSED] when no permission applies. A cancellation passes
     * through.
     */
    suspend fun guarded(permissions: List<String>, read: suspend () -> DataResult): DataResult =
        try {
            read()
        } catch (e: SecurityException) {
            if (permissions.isEmpty()) DataResult.Failed(REFUSED) else DataResult.NeedsPermission(permissions)
        }
}

/**
 * Runs [action] once no [call] runs: at once when none runs, else when the last one ends, by cancellation too. The port
 * drops the task's media table this way, so that it never clears it under a running `find_media` or `share_media`.
 */
internal class AfterCalls(private val action: () -> Unit) {
    private val lock = Any()
    private var running = 0
    private var wanted = false

    suspend fun <T> call(block: suspend () -> T): T {
        synchronized(lock) { running++ }
        try {
            return block()
        } finally {
            synchronized(lock) {
                running--
                if (running == 0 && wanted) {
                    wanted = false
                    action()
                }
            }
        }
    }

    fun request() {
        synchronized(lock) {
            if (running == 0) action() else wanted = true
        }
    }
}
