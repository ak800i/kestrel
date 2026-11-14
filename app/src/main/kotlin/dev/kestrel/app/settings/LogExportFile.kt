package dev.kestrel.app.settings

import android.content.ContentResolver
import android.net.Uri
import dev.kestrel.privacy.logs.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Writes both logs to the file at [uri] and returns whether that worked. The mode is "wt" because with "w" some
 * document providers keep the end of a longer existing file, and the export would then no longer be valid JSON.
 */
internal suspend fun writeLogExport(resolver: ContentResolver, uri: Uri, logs: LogStore): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val output = resolver.openOutputStream(uri, "wt") ?: return@withContext false
            output.use { logs.export(it) }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
