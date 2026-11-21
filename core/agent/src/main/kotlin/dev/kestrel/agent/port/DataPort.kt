package dev.kestrel.agent.port

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The data tools (design D12 of sub-project 5). `:core:datatools`' `AndroidDataPort` (plan 5B) implements it; the tests
 * use the fixtures' `FakeData`. Field values are untrusted and come unquoted and unredacted: `:core:agent` redacts and
 * quotes them (design D3, D17).
 */
interface DataPort {
    suspend fun findContact(name: String): DataResult

    suspend fun findMedia(from: LocalDate, until: LocalDate, kinds: MediaKinds): DataResult

    /** Shares the task's media items ([items] are item numbers such as `m1`) with [packageName]. */
    suspend fun shareMedia(packageName: String, items: List<String>): DataResult

    suspend fun listEvents(from: LocalDate, to: LocalDate): DataResult

    suspend fun createEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?): DataResult

    suspend fun setAlarm(hour: Int, minute: Int, label: String?, days: List<DayOfWeek>): DataResult

    suspend fun setTimer(seconds: Int, label: String?): DataResult

    suspend fun listNotifications(): DataResult

    suspend fun listSms(from: String?, limit: Int): DataResult

    suspend fun composeSms(number: String, text: String): DataResult

    suspend fun listCalls(limit: Int): DataResult

    suspend fun getLocation(): DataResult

    suspend fun mediaControl(action: MediaAction): DataResult

    suspend fun navigate(app: NavigationApp, to: String, mode: TravelMode?): DataResult

    /** Drops the task's media item table. */
    fun forgetTask()
}

enum class MediaKinds { PHOTOS, VIDEOS, BOTH }

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

enum class NavigationApp { WAZE, GOOGLE_MAPS }

enum class TravelMode { DRIVE, BICYCLE, WALK, TWO_WHEELER }

enum class AccessKind { NOTIFICATIONS }

sealed interface DataResult {
    /**
     * The tool's items: [note] is Kestrel's own text ("Android gave Kestrel only the photos that you selected"), and
     * [opened] the package that an intent tool opened, for `ScreenPort.waitForFront`.
     */
    data class Ok(val lines: List<DataLine>, val note: String? = null, val opened: String? = null) : DataResult {
        override fun toString(): String = "Ok(lines=${lines.size}, note=${note != null}, opened=${opened != null})"
    }

    data class NeedsPermission(val permissions: List<String>) : DataResult

    data class NeedsAccess(val kind: AccessKind) : DataResult

    /** [reason] is one of Kestrel's fixed sentences. */
    data class Failed(val reason: String) : DataResult {
        override fun toString(): String = "Failed(reason=${reason.length} chars)"
    }
}

/** One item: [fixed] is Kestrel's own text, the [fields] are untrusted. */
data class DataLine(val fixed: String, val fields: List<DataField>) {
    override fun toString(): String = "DataLine(fixed=${fixed.length} chars, fields=${fields.size})"
}

data class DataField(val name: String, val value: String) {
    override fun toString(): String = "DataField(name=$name, value=${value.length} chars)"
}
