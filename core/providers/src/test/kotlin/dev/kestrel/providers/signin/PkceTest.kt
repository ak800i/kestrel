package dev.kestrel.providers.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PkceTest {
    @Test
    fun theChallengeIsRfc7636sS256AndTheRandomValuesHaveTheirLengths() {
        // RFC 7636, Appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals(43, Pkce.verifier().length)
        assertEquals(22, Pkce.state().length)
        assertEquals(22, Pkce.nonce().length)
        assertNotEquals(Pkce.state(), Pkce.state())
    }
}
