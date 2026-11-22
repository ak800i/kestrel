package dev.kestrel.datatools.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.MediaKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * `find_media` and `share_media` (design D12 of sub-project 5): finds the owner's photos and videos of the days asked for
 * in MediaStore, and keeps for the task a table of item numbers (`m1`, `m2`, …) and their URIs, which the model never
 * sees; `share_media` builds its intent from that table. [granted] tells whether Kestrel holds a permission. One per
 * task's data port; [forget] drops the table when the task ends.
 */
class MediaTool(
    private val resolver: ContentResolver,
    private val granted: (String) -> Boolean,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    private class Item(val id: String, val uri: Uri, val video: Boolean)

    private class Found(val uri: Uri, val video: Boolean, val taken: Long, val size: Long)

    private val items = LinkedHashMap<Uri, Item>()

    suspend fun find(from: LocalDate, until: LocalDate, kinds: MediaKinds, zone: ZoneId): DataResult {
        val access = MediaQuery.access(kinds, sdk, granted)
        if (access == MediaQuery.Access.NONE) return DataResult.NeedsPermission(MediaQuery.permissions(kinds, sdk))
        val (start, end) = MediaQuery.bounds(from, until, zone)
        val found = withContext(Dispatchers.IO) {
            val photos = if (kinds != MediaKinds.VIDEOS) query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false, start, end) else emptyList()
            val videos = if (kinds != MediaKinds.PHOTOS) query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, start, end) else emptyList()
            (photos + videos).sortedBy { it.taken }
        }
        val lines = found.take(MediaQuery.MAX_ITEMS).map { item ->
            val known = items.getOrPut(item.uri) { Item("m${items.size + 1}", item.uri, item.video) }
            DataLine(MediaQuery.line(known.id, item.video, item.taken, item.size, zone), emptyList())
        }
        val notes = listOfNotNull(
            MediaQuery.PARTIAL.takeIf { access == MediaQuery.Access.SELECTED },
            "Kestrel lists the first ${MediaQuery.MAX_ITEMS} of ${found.size} items.".takeIf { found.size > MediaQuery.MAX_ITEMS },
        )
        return DataResult.Ok(lines, note = notes.joinToString(" ").ifEmpty { null })
    }

    /** The item numbers of [ids] that this task's `find_media` never gave. */
    fun unknown(ids: List<String>): List<String> {
        val known = items.values.map { it.id }.toSet()
        return ids.filter { it !in known }
    }

    /** The share intent of the task's items [ids] to [packageName]; every one of them must be known ([unknown]). */
    fun shareIntent(packageName: String, ids: List<String>): Intent {
        val byId = items.values.associateBy { it.id }
        val chosen = ids.distinct().map { byId.getValue(it) }
        val videos = chosen.count { it.video }
        return ShareIntent.build(packageName, chosen.map { it.uri }, ShareIntent.mimeType(chosen.size - videos, videos))
    }

    fun forget() = items.clear()

    private fun query(base: Uri, video: Boolean, start: Long, end: Long): List<Found> {
        val found = mutableListOf<Found>()
        val selection = "((${MediaStore.MediaColumns.DATE_TAKEN} >= ? AND ${MediaStore.MediaColumns.DATE_TAKEN} < ?) OR " +
            "((${MediaStore.MediaColumns.DATE_TAKEN} IS NULL OR ${MediaStore.MediaColumns.DATE_TAKEN} = 0) AND " +
            "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.DATE_ADDED} < ?))"
        val arguments = arrayOf(start.toString(), end.toString(), (start / 1000).toString(), (end / 1000).toString())
        resolver.query(base, COLUMNS, selection, arguments, null)?.use { rows ->
            while (rows.moveToNext()) {
                val taken = if (rows.isNull(1)) null else rows.getLong(1)
                found += Found(ContentUris.withAppendedId(base, rows.getLong(0)), video, MediaQuery.taken(taken, rows.getLong(2)), rows.getLong(3))
            }
        }
        return found
    }

    override fun toString(): String = "MediaTool(items=${items.size})"

    private companion object {
        val COLUMNS = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE)
    }
}
