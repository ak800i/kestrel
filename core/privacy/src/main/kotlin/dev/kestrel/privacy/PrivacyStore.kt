package dev.kestrel.privacy

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogStore
import dev.kestrel.privacy.settings.SettingsStore

/**
 * Kestrel's encrypted storage: the activity log, the network log, the settings, the providers' credentials and the past
 * conversations (design spec, sections 10.7 and 13.4; design 9 of sub-project 7). App code should get the stores from
 * `KestrelApplication.graph` instead of creating another `PrivacyStore`.
 */
class PrivacyStore(context: Context, clock: () -> Long = System::currentTimeMillis) {
    private val dataKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.DATA_KEY) }
    private val cipher = AesGcmFieldCipher { dataKey }
    private val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val credentialsKey by lazy { KeystoreKeys.getOrCreate(KeystoreKeys.CREDENTIALS_KEY, strongBox) }
    private val database = KestrelDatabase.get(context)

    val logs = LogStore(database.logDao(), cipher, clock)
    val settings = SettingsStore(
        database.settingsDao(),
        cipher,
        onUnreadable = { name ->
            logs.append(ActivityEvent(ActivityKind.ERROR, "The setting '$name' could not be read, so Kestrel uses its default"))
        },
    )

    /**
     * The providers' credentials, under their own key, in StrongBox when the device has it (design spec, section 10.7). A
     * credential that cannot be read is reported only to Android's log, without its ID, and never to the activity log; its
     * profile then asks the owner to sign in again.
     */
    val credentials = CredentialStore(
        database.credentialsDao(),
        AesGcmFieldCipher { credentialsKey },
        onUnreadable = { Log.w(TAG, "A stored credential could not be read") },
    )

    /**
     * The past conversations, under their own key, which "delete history" erases with the rows, so that no copy of the
     * database file can be read afterwards (design 9.1 and 9.7 of sub-project 7). A conversation in a format that this
     * version cannot read is kept and reported to the activity log by its kind only, never by its id or its words; one that
     * fails its check is deleted and reported by plan 7B's store, which the app graph wires (design 9.2).
     */
    val conversations = ConversationArchiveStore(
        database.conversationDao(),
        KeystoreErasableKey.of(KeystoreKeys.CONVERSATIONS_KEY),
        onUnknownFormat = {
            logs.append(ActivityEvent(ActivityKind.ERROR, "A past conversation is in a format that this version of Kestrel cannot read, so Kestrel keeps it"))
        },
    )

    private companion object {
        const val TAG = "Kestrel"
    }
}