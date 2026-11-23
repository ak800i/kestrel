package dev.kestrel.build

import java.io.File

/**
 * The key that signs Kestrel's release build, which four environment variables name (design spec, section 17). Its text
 * form leaves out both passwords.
 */
data class ReleaseSigning(val keystore: File, val storePassword: String, val keyAlias: String, val keyPassword: String) {
    override fun toString(): String = "ReleaseSigning(keystore=$keystore, keyAlias=$keyAlias)"

    companion object {
        const val KEYSTORE = "KESTREL_KEYSTORE"
        const val STORE_PASSWORD = "KESTREL_KEYSTORE_PASSWORD"
        const val KEY_ALIAS = "KESTREL_KEY_ALIAS"
        const val KEY_PASSWORD = "KESTREL_KEY_PASSWORD"

        /** The four variables, in the order in which the messages name them. */
        val VARIABLES = listOf(KEYSTORE, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)

        /**
         * The key that the variables, which [read] reads, name; null when none of them is set, or each is empty, so that
         * the release build stays unsigned. Throws IllegalStateException, whose message names variables but never their
         * values, when only some are set or the keystore is no file.
         */
        fun from(read: (String) -> String?): ReleaseSigning? {
            val values = VARIABLES.associateWith { name -> read(name)?.takeIf { it.isNotEmpty() } }
            if (values.values.all { it == null }) return null
            val missing = VARIABLES.filter { values[it] == null }
            check(missing.isEmpty()) { "Set all four release-signing variables or none; missing: ${missing.joinToString()}" }
            val keystore = File(values.getValue(KEYSTORE)!!)
            check(keystore.isFile) { "$KEYSTORE names no file" }
            return ReleaseSigning(
                keystore,
                values.getValue(STORE_PASSWORD)!!,
                values.getValue(KEY_ALIAS)!!,
                values.getValue(KEY_PASSWORD)!!,
            )
        }
    }
}
