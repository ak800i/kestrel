package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.styles.AdapterHarness
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BedrockModelsTest {
    private val bench = AdapterHarness()

    @After
    fun close() = bench.close()

    @Test
    fun theListKeepsActiveOnDemandModelsAndActiveProfilesWithGlobalProfilesFirst() {
        bench.reply("bedrock/foundation-models.json")
        bench.reply("bedrock/inference-profiles-1.json")
        bench.reply("bedrock/inference-profiles-2.json")

        val models = runBlocking { BedrockModels("us-east-1", NoAuth, bench.http).list() }

        val both = setOf(InputKind.TEXT, InputKind.IMAGE)
        assertEquals(
            listOf(
                DiscoveredModel("global.anthropic.claude-sonnet-5-v1:0", "Global Claude Sonnet 5", both),
                DiscoveredModel("us.anthropic.claude-sonnet-5-v1:0", "US Claude Sonnet 5", both),
                DiscoveredModel("amazon.nova-pro-v1:0", "Nova Pro", both),
            ),
            models,
        )
        assertEquals(
            listOf(
                "https://bedrock.us-east-1.amazonaws.com/foundation-models",
                "https://bedrock.us-east-1.amazonaws.com/inference-profiles?maxResults=1000",
                "https://bedrock.us-east-1.amazonaws.com/inference-profiles?maxResults=1000&nextToken=page-2",
            ).map { it.toHttpUrl() },
            bench.calls.requests,
        )
    }
}
