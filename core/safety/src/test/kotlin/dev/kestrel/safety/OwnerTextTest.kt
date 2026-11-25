package dev.kestrel.safety

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.IDN

/**
 * Whether the owner named a link himself (D32 item 12 of sub-project 5; spec 12.3): an https link's host by its labels in
 * one of his texts, any other link as a whole (review of plan 7A, N3 and N5).
 */
class OwnerTextTest {
    private class Case(val owner: List<String>, val link: String, val named: Boolean)

    private fun case(owner: String, link: String, named: Boolean) = Case(listOf(owner), link, named)

    @Test
    fun theOwnerNamesAHostOnlyByItsLabelsInOneOfHisTexts() {
        val cases = listOf(
            // Typed, typed as a whole link, spoken with "dot", in other letter case and with a trailing dot.
            case("open wikipedia.org", "https://wikipedia.org/w?search=kestrel", named = true),
            case("open https://kestrel.example.com/page", "https://kestrel.example.com/page?id=7", named = true),
            case("open wikipedia dot org", "https://wikipedia.org/w?search=kestrel", named = true),
            case("Open Kestrel.Example.COM.", "https://kestrel.example.com./page?id=7", named = true),
            // The labels run together, a lookalike host with a hyphen either way, and a subdomain.
            case("open wikipediaorg", "https://wikipedia.org/w?search=kestrel", named = false),
            case("open kestrel.example.com", "https://kestrel-example.com/page?id=7", named = false),
            case("open kestrel-example.com", "https://kestrel.example.com/page?id=7", named = false),
            case("open kestrel-example.com", "https://kestrel-example.com/page?id=7", named = true),
            case("open wikipedia.org", "https://en.wikipedia.org/w?search=kestrel", named = false),
            case("open en.wikipedia.org", "https://en.wikipedia.org/w?search=kestrel", named = true),
            // userinfo: the host is the one after the @.
            case("open kestrel.example", "https://kestrel.example@evil.example/page?id=7", named = false),
            // An international host is compared in its ASCII form, never folded to other letters.
            case("open čačak.rs", "https://${IDN.toASCII("čačak.rs")}/page?id=7", named = true),
            case("open čačak.rs", "https://cacak.rs/page?id=7", named = false),
            // A deep link is named only as a whole: its action word is not enough.
            case("send it on WhatsApp", "whatsapp://send?text=hi", named = false),
            case("open whatsapp://send?text=hi", "whatsapp://send?text=hi", named = true),
            // A number that the owner answered names no host of which it is only a part; an address that he gave names it.
            Case(listOf("Call the clinic", "7"), "https://7.example.com/page?id=7", named = false),
            case("open 192.168.1.1", "https://192.168.1.1/page?id=7", named = true),
            // Labels that two of his texts hold apart do not count.
            Case(listOf("open kestrel", "example.com"), "https://kestrel.example.com/page?id=7", named = false),
        )

        for (case in cases) assertEquals("${case.owner} → ${case.link}", case.named, LinkCheck.fromOwner(case.link, OwnerText.of(case.owner)))
    }
}
