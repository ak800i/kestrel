package dev.kestrel.privacy

import android.content.Context
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogStore
import dev.kestrel.privacy.settings.SettingsStore

/** Kestrel's encrypted storage: the activity log, the network log and the settings (design spec, section 13.4). */
class PrivacyStore(context: Context, clock: () -> Long = System::currentTimeMillis) {
    private val dataKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.DATA_KEY) }
    private val cipher = AesGcmFieldCipher { dataKey }
    private val database = KestrelDatabase.create(context)

    val logs = LogStore(database.logDao(), cipher, clock)
    val settings = SettingsStore(database.settingsDao(), cipher) { name ->
        logs.append(ActivityEvent(ActivityKind.ERROR, "The setting '$name' could not be read, so Kestrel uses its default"))
    }
}
