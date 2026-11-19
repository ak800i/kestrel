package dev.kestrel.app.debug

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.cost.Cost
import org.junit.Assert.assertEquals
import org.junit.Test

/** The answer of the hook provider-live: a case's line counts every call of the case, as the spending does (design D20). */
class ProviderHooksLineTest {
    private fun call(usage: Usage, credits: Double, priceKnown: Boolean, style: RequestStyle) = PricedResponse(
        ChatResponse(ChatMessage.Assistant(listOf(AssistantPart.Text("test"))), StopReason.END, usage, "test-model"),
        Cost(usd = credits * 0.04, credits = credits, estimated = usage.estimated, priceKnown = priceKnown, planUsage = true),
        style,
    )

    @Test
    fun theToolCasesLineAddsUpBothCalls() {
        val first = call(Usage(input = 100, output = 5, cacheRead = 20), credits = 0.010, priceKnown = true, style = RequestStyle.RESPONSES)
        val last = call(Usage(input = 130, output = 7, estimated = true), credits = 0.020, priceKnown = false, style = RequestStyle.ANTHROPIC)

        assertEquals(
            "case=tool outcome=ok matched=true style=ANTHROPIC input=250 output=12 estimated=true credits=0.030 priceKnown=false millis=900 standin=on",
            ProviderHooks.line("tool", true, listOf(first, last), 900, standIn = true),
        )
    }
}
