package dev.kestrel.datatools.calls

import android.Manifest
import android.content.ContentResolver
import android.provider.CallLog
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.datatools.DataLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** `list_calls` (design D12 of sub-project 5): the newest calls of the call history, at most [MAX]. */
class CallLogTool(private val resolver: ContentResolver, private val granted: (String) -> Boolean) {
    suspend fun list(limit: Int, zone: ZoneId): DataResult {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return DataResult.NeedsPermission(listOf(Manifest.permission.READ_CALL_LOG))
        val count = limit.coerceIn(1, MAX)
        val lines = withContext(Dispatchers.IO) {
            val found = mutableListOf<DataLine>()
            resolver.query(CallLog.Calls.CONTENT_URI, COLUMNS, null, null, "${CallLog.Calls.DATE} DESC")?.use { rows ->
                while (found.size < count && rows.moveToNext()) {
                    found += DataLines.call(rows.getInt(2), rows.getString(0), rows.getString(1), rows.getLong(3), rows.getLong(4), zone)
                }
            }
            found
        }
        return DataResult.Ok(lines)
    }

    companion object {
        const val MAX = 20

        private val COLUMNS = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
    }
}
