package dev.kestrel.privacy.crypto

import kotlinx.coroutines.sync.Mutex
import javax.crypto.SecretKey

/**
 * A key that can be erased (crypto-shredding): once it is erased, nothing that it encrypted can be read again, also not
 * from a copy of the database file. Its next use makes a new key (design 9.7 of sub-project 7).
 */
interface ErasableKey {
    /**
     * The lock that every store of data under this key and the key's erasure take, so that nothing is stored under a key
     * that is being erased. It belongs to the key, so every user of the key shares it.
     */
    val guard: Mutex

    /** The key; a new one when there is none. */
    fun get(): SecretKey

    /** Erases the key. Erasing a key that is already erased does nothing. */
    fun erase()
}

/**
 * An [ErasableKey] in Android Keystore (design 9.1 of sub-project 7). [of] gives one instance per alias and process, so
 * that every user of the alias shares its [guard] and none keeps a key that another one erased. It keeps the key after
 * its first use, as PrivacyStore's other keys are kept, until [erase] deletes it from Keystore. It is never made in
 * StrongBox, whose slower operations would make the first read of hundreds of conversations slow, as the data key is not.
 */
class KeystoreErasableKey private constructor(private val alias: String) : ErasableKey {
    override val guard = Mutex()

    private var key: SecretKey? = null

    @Synchronized
    override fun get(): SecretKey = key ?: KeystoreKeys.getOrCreate(alias).also { key = it }

    @Synchronized
    override fun erase() {
        key = null
        KeystoreKeys.delete(alias)
    }

    companion object {
        private val keys = HashMap<String, KeystoreErasableKey>()

        /** The key object of [alias] in this process. */
        @Synchronized
        fun of(alias: String): KeystoreErasableKey = keys.getOrPut(alias) { KeystoreErasableKey(alias) }
    }
}
