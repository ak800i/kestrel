package dev.kestrel.privacy.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.SecretKey
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * What "delete history" did with the key, once its transaction deleted everything stored under it (design 9.7 of
 * sub-project 7).
 */
enum class KeyErasure {
    /** The key is gone, so that no copy of what it encrypted can be read any more. */
    ERASED,

    /** The key could not be erased; the next [ErasableKey.storing] erases it before it stores anything. */
    KEPT,
}

/**
 * A key that can be erased (crypto-shredding): once it is erased, nothing that it encrypted can be read again, also not
 * from a copy of the database file. Only a store of data makes a new key, at its next use (design 9.7 of sub-project
 * 7); a read never makes one, so that a read after the erasure leaves no key behind.
 *
 * The key object also knows what this process knows of the key: whether it has made sure that the key is not one that
 * an empty store of data kept, which a process that ended between a deletion and the key's erasure, or an erasure that
 * failed, leaves. One key object per key and process ([KeystoreErasableKey.of]) makes that knowledge, and [guard], the
 * process's, whichever store of data uses the key.
 */
abstract class ErasableKey {
    /**
     * The lock that every store of data under this key and the key's erasure take, so that nothing is stored under a key
     * that is being erased. It belongs to the key, so every user of the key shares it.
     */
    val guard = Mutex()

    // Whether this process has made sure that the key is not one that an empty store of data kept: false at the start
    // of each process and after an erasure that failed. Used only under [guard].
    private var checked = false

    /** The key to store data with, under [guard]; a new one when there is none. */
    abstract fun get(): SecretKey

    /**
     * Runs [block] with the key, or with null when there is none, and never makes one: what a read decrypts with. [erase]
     * waits until [block] has returned, so that the key is never deleted while [block] uses it. [block] must not call
     * [get] or [erase].
     */
    abstract fun <T> withExisting(block: (SecretKey?) -> T): T

    /** Erases the key, once no [withExisting] uses it. Erasing a key that is already erased does nothing. */
    abstract fun erase()

    /**
     * Runs [store], which stores data under the key, under [guard] on [dispatcher]. The first store of each process,
     * and the first after an erasure that failed, first erases the key when [nothingStored] says that nothing is stored
     * under it, so that a key that "delete history" left is never used again (design 9.7 of sub-project 7).
     */
    suspend fun <T> storing(dispatcher: CoroutineDispatcher, nothingStored: () -> Boolean, store: () -> T): T = guard.withLock {
        withContext(dispatcher) {
            if (!checked) {
                if (nothingStored()) erase()
                checked = true
            }
            store()
        }
    }

    /**
     * "Delete history" (design 9.7 of sub-project 7), under [guard] on [dispatcher]: runs [deletion], which deletes
     * everything stored under the key in one transaction, and then erases the key, so that not even a copy of what was
     * stored can be decrypted any more. It throws only when [deletion] throws, which leaves the key as it was. Once
     * [deletion] has returned, it returns whether the key went: [KeyErasure.KEPT] when the erasure failed, which the
     * next [storing] erases before it stores anything. Once it holds [guard], the deletion and its result are one unit
     * that cancellation cannot split: a caller cancelled meanwhile still gets the result.
     */
    suspend fun deleting(dispatcher: CoroutineDispatcher, deletion: () -> Unit): KeyErasure = guard.withLock {
        // The hop to [dispatcher] runs inside NonCancellable, so that neither its return nor the outer one's checks the
        // caller's cancellation.
        withContext(NonCancellable) {
            withContext(dispatcher) {
                deletion()
                checked = false
                try {
                    erase()
                    checked = true
                    KeyErasure.ERASED
                } catch (e: Exception) {
                    KeyErasure.KEPT
                }
            }
        }
    }
}

/**
 * An [ErasableKey] in Android Keystore (design 9.1 of sub-project 7). [of] gives one instance per alias and process, so
 * that every user of the alias shares its [guard] and what the process knows of the key, and none keeps a key that
 * another one erased. It keeps the key after its first use, as PrivacyStore's other keys are kept, until [erase]
 * deletes it from Keystore. It is never made in StrongBox, whose slower operations would make the first read of
 * hundreds of conversations slow, as the data key is not.
 *
 * Reads share [use]'s read lock, so that many decrypt at once; [get] and [erase] take its write lock, so that the key is
 * never deleted from Keystore while a read decrypts with it, which would fail with Keystore's exception.
 */
class KeystoreErasableKey private constructor(private val alias: String) : ErasableKey() {
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

    /**
     * Forgets the key that this object keeps, so that its next use looks it up in Keystore, as after a start of
     * Kestrel.
     */
    internal fun forgetCachedKey() {
        use.write { key = null }
    }

    companion object {
        private val keys = HashMap<String, KeystoreErasableKey>()

        /** The key object of [alias] in this process. */
        @Synchronized
        fun of(alias: String): KeystoreErasableKey = keys.getOrPut(alias) { KeystoreErasableKey(alias) }
    }
}
