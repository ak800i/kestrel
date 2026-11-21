package dev.kestrel.safety

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 16.2's redaction cases (design D17 and D32 items 13 and 14 of sub-project 5). */
class RedactorTest {
    private fun redactor() = Redactor(Vault())

    @Test
    fun aCodeNearItsKeywordInEitherOrderAndBothScripts() {
        val redactor = redactor()

        assertEquals("Your verification code is [OTP_1]", redactor.redact("Your verification code is 483920"))
        assertEquals("[OTP_2] je vaš kôd", redactor.redact("517204 je vaš kôd"))
        assertEquals("Ваша шифра: [OTP_3]", redactor.redact("Ваша шифра: 7731"))
        assertEquals("PIN [OTP_4], lozinka [OTP_5]", redactor.redact("PIN 2468, lozinka 135-790"))
        assertEquals("Code: [OTP_6]", redactor.redact("Code: 482 913"))
    }

    @Test
    fun aCodeBesideItsKeywordInAnotherElementOfTheWindow() {
        val redactor = redactor()

        assertEquals("[OTP_1]", redactor.redact("483920", neighbours = listOf("Your verification code", "Expires in 10 minutes")))
        assertEquals("483920", redactor().redact("483920", neighbours = listOf("Your order", "Expires in 10 minutes")))
    }

    @Test
    fun numbersWithoutAKeywordDatesAmountsAndPhoneNumbersStay() {
        val redactor = redactor()
        for (text in listOf(
            "Meet at 1530 in room 4012", "20.11.2026", "Total 1500 RSD", "+381 60 1234567", "Call 064 123 4567",
            "ana@example.com", "Order 55821 shipped",
        )) {
            assertEquals(text, text, redactor.redact(text))
        }
    }

    @Test
    fun aCardNumberThatPassesLuhnAlsoBeforeAYear() {
        val redactor = redactor()

        assertEquals("Card [CARD_1] exp 12/27", redactor.redact("Card 4111 1111 1111 1111 exp 12/27"))
        assertEquals("[CARD_2] 2027", redactor.redact("5500-0000-0000-0004 2027"))
        assertEquals("4111 1111 1111 1112", redactor.redact("4111 1111 1111 1112"))
    }

    @Test
    fun ibansWithAValidChecksumAndValidJmbgNumbers() {
        val redactor = redactor()

        assertEquals("Pay to [IBAN_1] today", redactor.redact("Pay to RS35 2600 0560 1001 6113 79 today"))
        // A wrong check digit makes no IBAN (its digit groups may still pass as a card number).
        assertEquals(false, redactor.redact("RS35 2600 0560 1001 6113 78").contains("[IBAN"))
        assertEquals("JMBG: [JMBG_1]", redactor.redact("JMBG: 0101990710008"))
        assertEquals("0101990710009", redactor.redact("0101990710009"))
    }

    @Test
    fun anIbanEndsWithItsGroupsAndAFlightNumberBeforeWordsIsNone() {
        val redactor = redactor()

        assertEquals("Flight AH38 departs from gate B at noon", redactor.redact("Flight AH38 departs from gate B at noon"))
        assertEquals(
            "Flight AH38 departs from gate B at noon, boarding soon",
            redactor.redact("Flight AH38 departs from gate B at noon, boarding soon"),
        )
        assertEquals("To [IBAN_1].", redactor.redact("To RS35260005601001611379."))
        assertEquals("[IBAN_2] 2027", redactor.redact("BE68 5390 0754 7034 2027"))
        // Saudi Arabia is not in the table of lengths, so the whole run of groups must pass mod 97.
        assertEquals("IBAN [IBAN_3] ok", redactor.redact("IBAN SA03 8000 0000 6080 1016 7519 ok"))
    }

    @Test
    fun anIbanOutsideTheTableIsFoundBeforeAShortGroup() {
        val redactor = redactor()

        assertEquals("IBAN [IBAN_1] EUR", redactor.redact("IBAN LT12 1000 0111 0100 1000 EUR"))
        assertEquals("[IBAN_2] SAR", redactor.redact("SA03 8000 0000 6080 1016 7519 SAR"))
        assertEquals("[IBAN_2] 2027", redactor.redact("SA03 8000 0000 6080 1016 7519 2027"))
        // A run that only starts like an IBAN does not hide the IBAN after it.
        assertEquals("ab12 cdef [IBAN_3]", redactor.redact("ab12 cdef DE89 3704 0044 0532 0130 00"))
    }

    @Test
    fun anIbanInSmallLettersOfATableCountryIsRedacted() {
        val redactor = redactor()

        assertEquals("Pay to [IBAN_1] today", redactor.redact("Pay to rs35 2600 0560 1001 6113 79 today"))
        assertEquals("[IBAN_1]", redactor.redact("Rs35 2600 0560 1001 6113 79"))
        assertEquals("[IBAN_2].", redactor.redact("de89370400440532013000."))
        assertEquals("RS35260005601001611379", redactor.vault.valueOf("[IBAN_1]"))
        // Small letters count only for a country of the table, at its exact length.
        assertEquals("lt12 1000 0111 0100 1000", redactor.redact("lt12 1000 0111 0100 1000"))
        assertEquals("flight ah38 departs from gate b at noon", redactor.redact("flight ah38 departs from gate b at noon"))
    }

    @Test
    fun noBreakNarrowAndThinSpacesSplitGroupsLikeSpaces() {
        val redactor = redactor()

        assertEquals("Card [CARD_1]", redactor.redact("Card 4111\u00A01111\u00A01111\u00A01111"))
        assertEquals("Code: [OTP_1]", redactor.redact("Code: 482\u00A0913"))
        assertEquals("PIN [OTP_2]", redactor.redact("PIN 7731\u202F05"))
        assertEquals("Your code is [OTP_3]", redactor.redact("Your code is 55\u200912\u200990"))
        assertEquals("Pay to [IBAN_1]", redactor.redact("Pay to RS35\u00A02600\u00A00560\u00A01001\u00A06113\u00A079"))
        // A known value is found again across these spaces too.
        assertEquals("Again [OTP_1]", redactor.redact("Again 482\u202F913"))
        assertEquals("We sent a code to +381\u00A060\u00A01234567", redactor.redact("We sent a code to +381\u00A060\u00A01234567"))
    }

    @Test
    fun aRevealedCodeOrCardHasNoSeparators() {
        val redactor = redactor()

        assertEquals("Code: [OTP_1]", redactor.redact("Code: 482 913"))
        assertEquals("Card [CARD_1]", redactor.redact("Card 4111-1111-1111-1111"))
        assertEquals("482913 4111111111111111", redactor.vault.reveal("[OTP_1] [CARD_1]"))
    }

    @Test
    fun tokensKeysAndJwtsButNotAUrlSlug() {
        val redactor = redactor()
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"

        assertEquals("Bearer [TOKEN_1]", redactor.redact("Bearer $jwt"))
        assertEquals("key [TOKEN_2]", redactor.redact("key sk-proj-AbCdEfGhIjKlMnOpQrSt1234"))
        assertEquals("[TOKEN_3]", redactor.redact("ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"))
        assertEquals("id [TOKEN_4]", redactor.redact("id AKIAIOSFODNN7EXAMPLE"))
        assertEquals("[TOKEN_5]", redactor.redact("Q2xhdWRlIGlzIGEgbW9kZWwgYnkgQW50aHJvcGlj9x"))
        val slug = "https://example.com/best-places-to-visit-in-serbia-2026-guide"
        assertEquals(slug, redactor.redact(slug))
    }

    @Test
    fun aLiteralPlaceholderOnTheScreenBecomesParentheses() {
        val redactor = redactor()

        assertEquals("Type (OTP_1) into the field", redactor.redact("Type [OTP_1] into the field"))
        assertEquals(true, redactor.vault.isEmpty)
    }

    @Test
    fun theSameValueKeepsItsPlaceholderAndAKnownValueIsReplacedWithoutAKeyword() {
        val redactor = redactor()

        assertEquals("Your code is [OTP_1]", redactor.redact("Your code is 483920"))
        assertEquals("Codes: [OTP_1] and [OTP_2]", redactor.redact("Codes: 483920 and 771204"))
        assertEquals("Last messages: [OTP_1], see you", redactor.redact("Last messages: 483 920, see you"))
        assertEquals("483920", redactor.vault.valueOf("[OTP_1]"))
    }

    @Test
    fun aStatusBarIconWithALoginCode() {
        assertEquals(
            "Telegram: Login code: [OTP_1]. Do not give this code to anyone",
            redactor().redact("Telegram: Login code: 52817. Do not give this code to anyone"),
        )
    }

    @Test
    fun twoCardsInARunAreBothFoundAndAPhoneNumberNearCodeStays() {
        assertEquals("[CARD_1] [CARD_2]", redactor().redact("4111 1111 1111 1111 5500 0000 0000 0004"))
        assertEquals("We sent a code to +381 60 1234567", redactor().redact("We sent a code to +381 60 1234567"))
        assertEquals("Your code is [OTP_1]", redactor().redact("Your code is 4839 20"))
        // A code in three groups (review 2-2 New 3).
        assertEquals("Your code is [OTP_1]", redactor().redact("Your code is 12 34 56"))
    }
}
