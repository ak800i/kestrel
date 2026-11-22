package dev.kestrel.datatools.media

import android.content.ClipData
import android.content.Intent
import android.net.Uri

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

    fun build(packageName: String, uris: List<Uri>, mimeType: String): Intent {
        require(uris.isNotEmpty()) { "A share needs at least one item" }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return intent.setPackage(packageName).setType(mimeType).apply { clipData = clip }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
