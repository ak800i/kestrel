package dev.kestrel.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {
    @Test
    fun aValueKeepsItsPlaceholderAndIsRevealedOnlyFromItsOwnPlaceholders() {
        val vault = Vault()

        assertEquals("[OTP_1]", vault.placeholderFor(SecretKind.OTP, "483920"))
        assertEquals("[OTP_1]", vault.placeholderFor(SecretKind.OTP, "483 920"))
        assertEquals("[OTP_2]", vault.placeholderFor(SecretKind.OTP, "771204"))
        assertEquals("[CARD_1]", vault.placeholderFor(SecretKind.CARD, "4111 1111 1111 1111"))
        assertEquals("Code 483920, then [OTP_9]", vault.reveal("Code [OTP_1], then [OTP_9]"))
        assertEquals(listOf("[OTP_1]"), vault.placeholdersIn("Code [OTP_1], then [OTP_9]"))
        assertEquals(SecretKind.CARD, vault.kindOf("[CARD_1]"))
        assertEquals("Type a one-time code", vault.spoken("Type [OTP_2]"))
    }

    @Test
    fun clearingEmptiesItAndItsTextFormHoldsNoValue() {
        val vault = Vault()
        vault.placeholderFor(SecretKind.TOKEN, "ghp_secretsecretsecretsecret")

        assertFalse(vault.toString().contains("ghp_"))
        assertEquals("Vault(values=1)", vault.toString())
        vault.clear()
        assertTrue(vault.isEmpty)
        assertNull(vault.valueOf("[TOKEN_1]"))
        assertEquals("[TOKEN_1]", vault.placeholderFor(SecretKind.TOKEN, "another"))
    }
}
