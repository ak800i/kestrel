package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.AdapterHarness
import dev.kestrel.providers.styles.SigV4Auth
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

    @Test
    fun awsTokenErrorsAreTheCredentialsRefusal() {
        bench.status(403, """{"message":"The security token included in the request is invalid."}""", "x-amzn-ErrorType" to "UnrecognizedClientException")
        bench.status(403, """{"message":"The security token included in the request is expired"}""", "x-amzn-ErrorType" to "ExpiredTokenException")
        bench.status(403, """{"message":"Signature expired"}""", "x-amzn-ErrorType" to "InvalidSignatureException")

        repeat(3) { assertEquals(ProviderError.SignInExpired("amazon-bedrock"), errorOf(BedrockModels("us-east-1", signer({ NOW }), bench.http))) }
    }

    @Test
    fun aClockSkewIsCorrectedOnceAndKeptForTheNextPages() {
        bench.status(403, """{"message":"Signature expired"}""", "x-amzn-ErrorType" to "InvalidSignatureException", "Date" to httpDate(NOW - 3_600_000))
        bench.status(200, """{"modelSummaries":[]}""")
        bench.status(200, """{"inferenceProfileSummaries":[],"nextToken":"page-2"}""")
        bench.status(200, """{"inferenceProfileSummaries":[]}""")

        val models = runBlocking { BedrockModels("us-east-1", signer({ NOW }), ProviderHttp(bench.calls, clock = { NOW })).list() }

        assertEquals(emptyList<DiscoveredModel>(), models)
        assertEquals(listOf("20261119T100000Z", "20261119T090000Z", "20261119T090000Z", "20261119T090000Z"), List(4) { bench.take().headers["x-amz-date"] })
    }

    @Test
    fun theProfilePagesStopWhenATokenRepeats() {
        bench.status(200, """{"modelSummaries":[]}""")
        bench.status(200, """{"inferenceProfileSummaries":[],"nextToken":"again"}""")
        bench.status(200, """{"inferenceProfileSummaries":[],"nextToken":"again"}""")
        bench.status(200, """{"inferenceProfileSummaries":[]}""")

        runBlocking { BedrockModels("us-east-1", NoAuth, bench.http).list() }

        assertEquals(3, bench.server.requestCount)
    }

    @Test
    fun theProfilePagesStopAfterFiftyPages() {
        bench.status(200, """{"modelSummaries":[]}""")
        repeat(60) { bench.status(200, """{"inferenceProfileSummaries":[],"nextToken":"page-$it"}""") }
        bench.status(200, """{"inferenceProfileSummaries":[]}""")

        runBlocking { BedrockModels("us-east-1", NoAuth, bench.http).list() }

        assertEquals(1 + 50, bench.server.requestCount)
    }

    private fun signer(clock: () -> Long) = SigV4Auth(Credential.AwsAccessKeys("AKIDEXAMPLE", "secret"), "us-east-1", clock = clock)

    private fun errorOf(discovery: ModelDiscovery): ProviderError =
        try {
            runBlocking { discovery.list() }
            throw AssertionError("expected a ProviderException")
        } catch (e: ProviderException) {
            e.error
        }

    private companion object {
        val NOW = Instant.parse("2026-11-19T10:00:00Z").toEpochMilli()

        fun httpDate(millis: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))
    }
}
