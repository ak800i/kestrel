package dev.kestrel.agent

import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import org.junit.Assert.assertEquals
import org.junit.Test

class DataResultTextTest {
    private val redactor = Redactor(Vault())

    @Test
    fun eachFieldIsRedactedWithItsLinesOtherFieldsAndThenQuoted() {
        val result = DataResult.Ok(listOf(
            DataLine("SMS 1 · 10:20", listOf(DataField("from", "Kod"), DataField("body", "483920"))),
            DataLine("SMS 2 · 09:00", listOf(DataField("from", "Ana"), DataField("body", "Say \"hi\"\tto Marko"))),
        ))

        assertEquals(
            "SMS 1 · 10:20 · from: \"Kod\" · body: \"[OTP_1]\"\n" +
                "SMS 2 · 09:00 · from: \"Ana\" · body: \"Say \\\"hi\\\" to Marko\"",
            DataResultText.of(result, redactor).text,
        )
    }

    @Test
    fun aLineBreakIsStillALineBreakWhenTheRedactorReadsIt() {
        val result = DataResult.Ok(listOf(DataLine("", listOf(DataField("body", "Poštovani,\nkod za potvrdu je 483920")))))

        assertEquals("body: \"Poštovani,\\nkod za potvrdu je [OTP_1]\"", DataResultText.of(result, redactor).text)
    }

    @Test
    fun anEmptyResultSaysSoAndKestrelsNoteFollows() {
        val result = DataResult.Ok(emptyList(), note = "Android gave Kestrel only the photos that you selected.")

        assertEquals("No items.\nAndroid gave Kestrel only the photos that you selected.", DataResultText.of(result, redactor).text)
    }
}
