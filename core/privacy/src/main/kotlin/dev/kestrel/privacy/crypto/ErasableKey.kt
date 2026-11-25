package dev.kestrel.privacy.crypto

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.SecretKey
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A key that can be erased (crypto-shredding): once it is erased, nothing that it encrypted can be read again, also not
 * from a copy of the database file. Only a store of data makes a new key, at its next use (design 9.7 of sub-project 7);
 * a read never makes one, so that a read after the erasure leaves no key behind.
 */
interface ErasableKey {
    /**
     * The lock that every store of data under this key and the key's erasure take, so that nothing is stored under a key
     * that is being erased. It belongs to the key, so every user of the key shares it.
     */
    val guard: Mutex

    /** The key to store data with, under [guard]; a new one when there is none. */
    fun get(): SecretKey

    /**
     * Runs [block] with the key, or with null when there is none, and never makes one: what a read decrypts with. [erase]
     * waits until [block] has returned, so that the key is never deleted while [block] uses it. [block] must not call
     * [get] or [erase].
     */
    fun <T> withExisting(block: (SecretKey?) -> T): T

    /** Erases the key, once no [withExisting] uses it. Erasing a key that is already erased does nothing. */
    fun erase()
}

/**
 * An [ErasableKey] in Android Keystore (design 9.1 of sub-project 7). [of] gives one instance per alias and process, so
 * that every user of the alias shares its [guard] and none keeps a key that another one erased. It keeps the key after
 * its first use, as PrivacyStore's other keys are kept, until [erase] deletes it from Keystore. It is never made in
 * StrongBox, whose slower operations would make the first read of hundreds of conversations slow, as the data key is not.
 *
 * Reads share [use]'s read lock, so that many decrypt at once; [get] and [erase] take its write lock, so that the key is
 * never deleted from Keystore while a read decrypts with it, which would fail with Keystore's exception.
 */
class KeystoreErasableKey private constructor(private val alias: String) : ErasableKey {
    override val guard = Mutex()

    private val use = ReentrantReadWriteLock()

    // Written under use's write lock, or under its read lock with the key that Keystore holds then, which no erasure can
    // delete meanwhile.
    @Volatile
    private var key: SecretKey? = null

    override fun get(): SecretKey = use.write { key ?: KeystoreKeys.getOrCreate(alias).also { key = it } }

    override fun <T> withExisting(block: (SecretKey?) -> T): T = use.read { block(key ?: KeystoreKeys.find(alias)?.also { key = it }) }

    override fun erase() {
        use.write {
            key = null
            KeystoreKeys.delete(alias)
        }
    }

    companion object {
        private val keys = HashMap<String, KeystoreErasableKey>()

        /** The key object of [alias] in this process. */
        @Synchronized
        fun of(alias: String): KeystoreErasableKey = keys.getOrPut(alias) { KeystoreErasableKey(alias) }
    }
}
