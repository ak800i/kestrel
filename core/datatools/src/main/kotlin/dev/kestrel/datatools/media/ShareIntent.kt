package dev.kestrel.datatools.media

import android.content.Intent
import android.net.Uri
import dev.kestrel.agent.port.IntentExtra
import dev.kestrel.agent.port.IntentSpec

/**
 * The intent of `share_media` (design D12 of sub-project 5; research r55): `ACTION_SEND` for one item, or
 * `ACTION_SEND_MULTIPLE` for several, to one app, with every item in the `ClipData` and the read grant, so that the app
 * may open them. It never carries an app's unofficial extras, such as WhatsApp's `jid`, so the app shows its own chat
 * picker and the agent picks the chat on the screen.
 */
object ShareIntent {
    /** Any image for photos only, any video for one video alone, and anything otherwise (Telegram's manifest; r55). */
    fun mimeType(photos: Int, videos: Int): String = when {
        videos == 0 -> "image/*"
        photos == 0 && videos == 1 -> "video/*"
        else -> "*/*"
    }

    fun build(packageName: String, uris: List<Uri>, mimeType: String): IntentSpec {
        require(uris.isNotEmpty()) { "A share needs at least one item" }
        val items = uris.map { it.toString() }
        val (action, stream) = if (items.size == 1) {
            Intent.ACTION_SEND to IntentExtra.ContentUri(items[0])
        } else {
            Intent.ACTION_SEND_MULTIPLE to IntentExtra.ContentUris(items)
        }
        return IntentSpec(action, packageName = packageName, extras = mapOf(Intent.EXTRA_STREAM to stream), type = mimeType, readable = items)
    }
}