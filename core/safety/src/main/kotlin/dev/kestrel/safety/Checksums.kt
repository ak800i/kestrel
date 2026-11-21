package dev.kestrel.safety

import java.time.DateTimeException
import java.time.LocalDate

/** The checksums of the values that redaction recognises (design D17 of sub-project 5). */
internal object Checksums {
    /** The Luhn check of a card number. */
    fun luhn(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        var sum = 0
        for ((position, char) in digits.reversed().withIndex()) {
            var value = char - '0'
            if (position % 2 == 1) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
        }
        return sum % 10 == 0
    }

    /** ISO 13616's mod-97 check of a compact IBAN in capital letters. */
    fun iban(compact: String): Boolean {
        if (compact.length < 15 || compact.length > 34) return false
        val moved = compact.substring(4) + compact.substring(0, 4)
        var rest = 0
        for (char in moved) {
            val value = when (char) {
                in '0'..'9' -> char - '0'
                in 'A'..'Z' -> char - 'A' + 10
                else -> return false
            }
            rest = if (value >= 10) (rest * 100 + value) % 97 else (rest * 10 + value) % 97
        }
        return rest == 1
    }

    /** A Serbian JMBG: 13 digits, a date that exists (DDMMYYY) and the check digit of its control rule. */
    fun jmbg(digits: String): Boolean {
        if (digits.length != 13 || digits.any { !it.isDigit() }) return false
        val d = digits.map { it - '0' }
        val sum = 7 * (d[0] + d[6]) + 6 * (d[1] + d[7]) + 5 * (d[2] + d[8]) + 4 * (d[3] + d[9]) + 3 * (d[4] + d[10]) + 2 * (d[5] + d[11])
        val control = (11 - sum % 11).let { if (it > 9) 0 else it }
        if (control != d[12]) return false
        val day = digits.substring(0, 2).toInt()
        val month = digits.substring(2, 4).toInt()
        val year = digits.substring(4, 7).toInt().let { if (it >= 800) 1000 + it else 2000 + it }
        return try {
            LocalDate.of(year, month, day)
            true
        } catch (e: DateTimeException) {
            false
        }
    }

    /** The IBAN length of each country that Kestrel knows (ISO 13616's registry); other countries take 15 to 34. */
    val IBAN_LENGTHS: Map<String, Int> = mapOf(
        "RS" to 22, "ME" to 22, "BA" to 20, "HR" to 21, "SI" to 19, "MK" to 19, "AL" to 28, "XK" to 20, "BG" to 22, "RO" to 24,
        "HU" to 28, "GR" to 27, "AT" to 20, "DE" to 22, "CH" to 21, "IT" to 27, "FR" to 27, "ES" to 24, "PT" to 25, "NL" to 18,
        "BE" to 16, "LU" to 20, "GB" to 22, "IE" to 22, "PL" to 28, "CZ" to 24, "SK" to 24, "SE" to 24, "NO" to 15, "DK" to 18,
        "FI" to 18, "TR" to 26,
    )
}
