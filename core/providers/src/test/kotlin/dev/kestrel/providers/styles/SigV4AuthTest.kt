package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * AWS's SigV4 test-suite vectors (`awslabs/aws-c-auth`, `tests/aws-signing-test-suite/v4/<case>/`: `context.json`,
 * `request.txt`, `header-canonical-request.txt` and `header-signed-request.txt`), and the double encoding of a model ID
 * in the canonical path that the AWS SDK for Java v2 uses (design D19).
 */
class SigV4AuthTest {
    private val clock = { Instant.parse("2015-08-30T12:36:00Z").toEpochMilli() }
    private val keys = Credential.AwsAccessKeys("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
    private val signer = SigV4Auth(keys, "us-east-1", "service", clock)

    private fun authorization(signed: String, signature: String) =
        "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=$signed, Signature=$signature"

    @Test
    fun getVanilla() {
        val headers = signer.sign("GET", "https://example.amazonaws.com/".toHttpUrl(), ByteArray(0))

        assertEquals("20150830T123600Z", headers["x-amz-date"])
        assertEquals(authorization("host;x-amz-date", "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"), headers["Authorization"])
    }

    @Test
    fun postVanilla() {
        val headers = signer.sign("POST", "https://example.amazonaws.com/".toHttpUrl(), ByteArray(0))

        assertEquals(authorization("host;x-amz-date", "5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b"), headers["Authorization"])
    }

    @Test
    fun getVanillaQueryOrderKeyCaseSortsTheQuery() {
        val url = "https://example.amazonaws.com/?Param2=value2&Param1=value1".toHttpUrl()

        assertEquals(
            "GET\n/\nParam1=value1&Param2=value2\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            signer.canonicalRequest("GET", url, ByteArray(0), "20150830T123600Z"),
        )
        assertEquals(authorization("host;x-amz-date", "b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500"), signer.sign("GET", url, ByteArray(0))["Authorization"])
    }

    @Test
    fun postStsHeaderBeforeSignsTheSessionToken() {
        val token = "AQoDYXdzEPT//////////wEXAMPLEtc764bNrC9SAPBSM22wDOk4x4HIZ8j4FZTwdQWLWsKWHGBuFqwAeMicRXmxfpSPfIeoIYRqTflfKD8YUuwthAx7mSEI/" +
            "qkPpKPi/kMcGdQrmGdeehM4IC1NtBmUpp2wUE8phUZampKsburEDy0KPkyQDYwT7WZ0wq5VSXDvp75YU9HFvlRd8Tx6q6fE8YQcHNVXAkiY9q6d+xo0rKwT38xVqr7ZD0u0iPPk" +
            "UL64lIZbqBAz+scqKmlzm8FDrypNC9Yjc8fPOLn9FX9KSYvKTr4rvx3iSIlTJabIQwj2ICCR/oLxBA=="
        val withToken = SigV4Auth(keys.copy(sessionToken = token), "us-east-1", "service", clock)

        val headers = withToken.sign("POST", "https://example.amazonaws.com/".toHttpUrl(), ByteArray(0))

        assertEquals(token, headers["x-amz-security-token"])
        assertEquals(authorization("host;x-amz-date;x-amz-security-token", "85d96828115b5dc0cfc3bd16ad9e210dd772bbebba041836c64533a82be05ead"), headers["Authorization"])
    }

    @Test
    fun aModelIdIsEncodedInTheUrlAndOnceMoreInTheCanonicalPath() {
        val arn = "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-sonnet-5-v1:0"
        val url = "https://bedrock-runtime.us-east-1.amazonaws.com".toHttpUrl().newBuilder()
            .addPathSegment("model").addEncodedPathSegment(SigV4Auth.encode(arn)).addPathSegment("converse").build()

        assertEquals("/model/arn%3Aaws%3Abedrock%3Aus-east-1%3A123456789012%3Ainference-profile%2Fus.anthropic.claude-sonnet-5-v1%3A0/converse", url.encodedPath)
        val canonicalPath = signer.canonicalRequest("POST", url, ByteArray(0), "20150830T123600Z").lines()[1]
        assertEquals("/model/arn%253Aaws%253Abedrock%253Aus-east-1%253A123456789012%253Ainference-profile%252Fus.anthropic.claude-sonnet-5-v1%253A0/converse", canonicalPath)
    }
}
