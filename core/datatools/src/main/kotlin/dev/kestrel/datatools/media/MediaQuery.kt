package dev.kestrel.datatools.media

import android.Manifest
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.MediaKinds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The plain rules of `find_media` (design D12 of sub-project 5; research r55, r65): which permissions it needs, which
 * days it reads, when an item was taken, and the line that names an item without its image, file name or path.
 */
object MediaQuery {
    /** `find_media` lists at most this many items. */
    const val MAX_ITEMS = 30

    const val PARTIAL = "Android gave Kestrel only the photos that you selected."

    /** How much of the owner's media Kestrel may read: all of the kinds asked for, only what he selected, or nothing. */
    enum class Access { FULL, SELECTED, NONE }

    // Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, which Android 14 (API 34) added.
    private const val USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    /**
     * The permissions that `find_media` asks for together: photos and videos, and on Android 14 and later the partial
     * access, which Android's dialog offers as "Select photos and videos".
     */
    fun permissions(kinds: MediaKinds, sdk: Int): List<String> =
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) +
            if (sdk >= 34) listOf(USER_SELECTED) else emptyList()

    fun access(kinds: MediaKinds, sdk: Int, granted: (String) -> Boolean): Access {
        val needed = when (kinds) {
            MediaKinds.PHOTOS -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            MediaKinds.VIDEOS -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
            MediaKinds.BOTH -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        }
        return when {
            needed.all(granted) -> Access.FULL
            sdk >= 34 && granted(USER_SELECTED) -> Access.SELECTED
            else -> Access.NONE
        }
    }

    /** The start of [from] and the start of the day after [until] in [zone], in milliseconds since the epoch. */
    fun bounds(from: LocalDate, until: LocalDate, zone: ZoneId): Pair<Long, Long> =
        from.atStartOfDay(zone).toInstant().toEpochMilli() to until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** When an item was taken: `DATE_TAKEN`, or `DATE_ADDED` (in seconds) when Android has no `DATE_TAKEN` (r65). */
    fun taken(dateTaken: Long?, dateAddedSeconds: Long): Long = if (dateTaken == null || dateTaken == 0L) dateAddedSeconds * 1000 else dateTaken

    /** "m2 · photo · 2026-11-19 12:05 · 2.2 MB": Kestrel's own words, so the line has no untrusted field. */
    fun line(id: String, video: Boolean, takenMillis: Long, sizeBytes: Long, zone: ZoneId): String =
        "$id · ${if (video) "video" else "photo"} · ${TIME.format(Instant.ofEpochMilli(takenMillis).atZone(zone))} · ${size(sizeBytes)}"

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    /**
     * `find_media`'s result of [found], which is in time order: the newest [MAX_ITEMS] items, still oldest first, so that
     * "the photo I just took" is always among them, and a last line without fields that tells the model how many items
     * the days have. The note carries only [PARTIAL], because Kestrel says it to the owner before a share (design D12).
     */
    fun <T> result(found: List<T>, access: Access, line: (T) -> String): DataResult.Ok {
        val lines = found.takeLast(MAX_ITEMS).map { DataLine(line(it), emptyList()) }
        val count = DataLine("Kestrel lists the newest $MAX_ITEMS of ${found.size} items.", emptyList()).takeIf { found.size > MAX_ITEMS }
        return DataResult.Ok(lines + listOfNotNull(count), note = PARTIAL.takeIf { access == Access.SELECTED })
    }
}
