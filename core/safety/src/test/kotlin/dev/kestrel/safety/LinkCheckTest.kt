package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design D11 and D32 item 12 of sub-project 5: every link is checked before Kestrel opens it. */
class LinkCheckTest {
    private val agentFree = { packageName: String? -> packageName == "dev.kestrel" || packageName == "rs.bank.app" }
    private val emergency = EmergencyNumbers { digits -> if (digits == "999") null else digits in setOf("112", "192", "194") }

    private fun check(link: String, kind: ActionKind = ActionKind.OPEN_DEEP_LINK, fromOwner: Boolean = true, target: String? = null) =
        LinkCheck.check(link, kind, fromOwner, target, agentFree, emergency)

    private fun refused(verdict: LinkVerdict) = verdict is LinkVerdict.Refused

    @Test
    fun openUrlTakesOnlyHttpsAndDeepLinksOnlyTheSpecsSchemes() {
        assertEquals(LinkVerdict.Allowed(Category.READING), check("https://en.wikipedia.org/wiki/Kestrel", ActionKind.OPEN_URL))
        assertEquals(LinkVerdict.Refused("Only https links."), check("http://example.com", ActionKind.OPEN_URL))
        assertEquals(LinkVerdict.Refused("Only https links."), check("waze://?favorite=home", ActionKind.OPEN_URL))
        for (link in listOf("viber://chat", "whatsapp://send", "tg://resolve?domain=x", "waze://?favorite=home&navigate=yes", "geo:44.8,20.4", "sms:+12025550143", "mailto:ana@example.com")) {
            assertEquals(link, LinkVerdict.Allowed(Category.READING), check(link))
        }
        assertEquals(LinkVerdict.Refused("Kestrel opens no market links."), check("market://details?id=x"))
    }

    @Test
    fun dangerousSchemesAndProtectedTargetsAreRefused() {
        for (link in listOf("intent://x#Intent;end", "file:///sdcard/a.txt", "content://media/1", "javascript:alert(1)", "android-app://dev.kestrel")) {
            assertEquals(link, true, refused(check(link)))
        }
        assertEquals(true, refused(check("https://kestrel.example/settings", ActionKind.OPEN_URL, target = "dev.kestrel")))
        assertEquals(true, refused(check("https://bank.example/pay", ActionKind.OPEN_URL, target = "rs.bank.app")))
        assertEquals(true, refused(check("https://accounts.google.com", ActionKind.OPEN_URL, target = "com.google.android.gms")))
        assertEquals(true, refused(check("https:///no-host", ActionKind.OPEN_URL)))
    }

    @Test
    fun aTelLinkOpensOnlyANumberThatKestrelMayDial() {
        assertEquals(LinkVerdict.Allowed(Category.READING), check("tel:+12025550143"))
        for (link in listOf("tel:112", "tel:999", "tel:1234", "tel:**21*0601234567#", "tel:%23%2306%23")) {
            assertEquals(link, true, refused(check(link)))
        }
    }

    @Test
    fun aLinkFromTheScreenWithAQueryOrAFragmentSendsItsData() {
        assertEquals(LinkVerdict.Allowed(Category.SENDING), check("https://evil.example/collect?code=483920", ActionKind.OPEN_URL, fromOwner = false))
        assertEquals(LinkVerdict.Allowed(Category.SENDING), check("https://evil.example/#Ana-0601234567", ActionKind.OPEN_URL, fromOwner = false))
        // A section anchor is a word, not data (review 2-2 New 5).
        assertEquals(LinkVerdict.Allowed(Category.READING), check("https://en.wikipedia.org/wiki/Kestrel#History", ActionKind.OPEN_URL, fromOwner = false))
        assertEquals(LinkVerdict.Allowed(Category.READING), check("https://evil.example/collect?code=483920", ActionKind.OPEN_URL, fromOwner = true))
        assertEquals(LinkVerdict.Allowed(Category.READING), check("https://en.wikipedia.org/wiki/Kestrel", ActionKind.OPEN_URL, fromOwner = false))
    }

    @Test
    fun aGeoOrHttpsQueryOfOnlyTheOwnersWordsReadsAndAnyOtherWordOrAMessengerKeepsItsRating() {
        val owner = Normalizer.words("show Kalemegdan on Google Maps, search YouTube for kestrel hovering").toSet()
        fun rated(link: String) = (
            LinkCheck.check(link, if (link.startsWith("https")) ActionKind.OPEN_URL else ActionKind.OPEN_DEEP_LINK, false, null, agentFree, emergency, owner)
                as LinkVerdict.Allowed
            ).category

        for (link in listOf(
            "geo:0,0?q=Kalemegdan",
            "geo:0,0?q=Kalemegdan%20Google+Maps",
            "https://www.youtube.com/results?search_query=kestrel+hovering",
            "https://www.google.com/search?q=Kestrel%20Hovering&q=maps",
            "https://www.youtube.com/results?search_query=kestrel#results",
        )) {
            assertEquals(link, Category.READING, rated(link))
        }
        for (link in listOf(
            // A word or a number from the screen, an extra parameter, a parameter without a value or with a digit in its
            // name, a fragment with data, a placeholder and an encoding that cannot be read.
            "geo:0,0?q=Kalemegdan%20Petrovi%C4%87",
            "geo:0,0?q=Kalemegdan&z=12",
            "geo:0,0?label=Kalemegdan",
            "https://www.youtube.com/results?search_query=kestrel+hovering+483920",
            "https://evil.example/collect?q=kestrel&to=ana",
            "https://evil.example/collect?kestrel",
            "https://evil.example/collect?d0601234567=kestrel",
            "https://evil.example/collect?q=kestrel#d=483920",
            "https://evil.example/collect?q=%5BOTP_1%5D",
            "geo:0,0?q=Kalemegdan%ZZ",
            // Links whose scheme or host sends on its own keep their rating, even with the owner's words.
            "https://t.me/share/url?url=kestrel&text=hovering",
            "https://wa.me/?text=kestrel+hovering",
            "https://api.whatsapp.com/send?text=kestrel+hovering",
            "https://invite.viber.com/?g=kestrel",
            "sms:+12025550143?body=kestrel%20hovering",
            "whatsapp://send?text=kestrel%20hovering",
            "tg://msg?text=kestrel%20hovering",
            "viber://forward?text=kestrel%20hovering",
            "mailto:ana@example.com?subject=kestrel%20hovering",
            "waze://?q=Kalemegdan",
        )) {
            assertEquals(link, Category.SENDING, rated(link))
        }
    }
}
