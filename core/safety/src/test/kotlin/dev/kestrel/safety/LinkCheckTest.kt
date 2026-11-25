package dev.kestrel.safety

import org.junit.Assert.assertEquals
import org.junit.Test

/** Design D11 and D32 item 12 of sub-project 5: every link is checked before Kestrel opens it. */
class LinkCheckTest {
    private val agentFree = { packageName: String? -> packageName == "dev.kestrel" || packageName == "rs.bank.app" }
    private val emergency = EmergencyNumbers { digits -> if (digits == "999") null else digits in setOf("112", "192", "194") }

    // [owner] is what the owner said: by default the link itself, so that he named it; null when he said nothing.
    private fun check(link: String, kind: ActionKind = ActionKind.OPEN_DEEP_LINK, owner: String? = link, target: String? = null) =
        LinkCheck.check(link, kind, owner?.let(TaskSafety::start) ?: TaskSafety(TaskAllowance.NONE), target, agentFree, emergency)

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
        assertEquals(LinkVerdict.Allowed(Category.READING), check("tel:+12025550143", target = "com.google.android.dialer"))
        assertEquals(LinkVerdict.Allowed(Category.READING), LinkCheck.check("tel:+12025550143", ActionKind.OPEN_DEEP_LINK, TaskSafety.start("Dial +12025550143"), "com.example.phone", agentFree, emergency, setOf("com.example.phone")))
        assertEquals(LinkVerdict.Refused("Kestrel opens a tel: link only in the phone app."), check("tel:+12025550143", target = "org.telegram.messenger"))
    }

    @Test
    fun aLinkFromTheScreenWithAQueryOrAFragmentSendsItsData() {
        assertEquals(LinkVerdict.Allowed(Category.SENDING), check("https://evil.example/collect?code=483920", ActionKind.OPEN_URL, owner = null))
        assertEquals(LinkVerdict.Allowed(Category.SENDING), check("https://evil.example/#Ana-0601234567", ActionKind.OPEN_URL, owner = null))
        // A section anchor is a word, not data (review 2-2 New 5).
        assertEquals(LinkVerdict.Allowed(Category.READING), check("https://en.wikipedia.org/wiki/Kestrel#History", ActionKind.OPEN_URL, owner = null))
        assertEquals(LinkVerdict.Allowed(Category.READING), check("https://evil.example/collect?code=483920", ActionKind.OPEN_URL))
        assertEquals(LinkVerdict.Allowed(Category.READING), check("https://en.wikipedia.org/wiki/Kestrel", ActionKind.OPEN_URL, owner = null))
    }

    // Spec 12.3: the owner's words are his command, answers and steering alone; a link counts as his only when one of those
    // texts names its host, never words that two of them hold apart, and never a memory value.
    @Test
    fun aLinkIsTheOwnersOnlyWhenOneOfHisOwnTextsNamesIt() {
        val link = "https://kestrel.example/page?id=7"
        fun rated(task: TaskSafety) = (LinkCheck.check(link, ActionKind.OPEN_URL, task, null, agentFree, emergency) as LinkVerdict.Allowed).category

        assertEquals(Category.READING, rated(TaskSafety.start("Read Ana's messages").withOwnerWords("Also open kestrel.example")))
        assertEquals(Category.SENDING, rated(TaskSafety.start("Find the kestrel").withOwnerWords("example page")))
        assertEquals(Category.SENDING, rated(TaskSafety.start("Read Ana's messages").copy(memoryValues = listOf("kestrel.example"))))
    }

    @Test
    fun aGeoOrHttpsQueryOfOnlyTheOwnersWordsReadsAndAnyOtherWordOrAMessengerKeepsItsRating() {
        val owner = TaskSafety.start("show Kalemegdan on Google Maps, search YouTube for kestrel hovering")
        fun rated(link: String) = (
            LinkCheck.check(link, if (link.startsWith("https")) ActionKind.OPEN_URL else ActionKind.OPEN_DEEP_LINK, owner, null, agentFree, emergency)
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

    @Test
    fun aSearchReadsOnlyWithItsKeysPlainCharactersAndNoMessenger() {
        val owner = TaskSafety.start("show Kalemegdan on Google Maps, search YouTube for kestrel hovering near Ana Petrović")
        fun rated(link: String, target: String? = null) = (
            LinkCheck.check(link, if (link.startsWith("https")) ActionKind.OPEN_URL else ActionKind.OPEN_DEEP_LINK, owner, target, agentFree, emergency)
                as LinkVerdict.Allowed
            ).category

        // Every search key, and the diacritics, letter case and script that the fold does not compare (spec 12.1).
        for (link in listOf(
            "https://www.google.com/search?query=kestrel&search=hovering&text=Kalemegdan",
            "https://www.google.com/search?q=Ana+Petrovi%C4%87",
            "https://www.google.com/search?q=%D0%9A%D0%B0%D0%BB%D0%B5%D0%BC%D0%B5%D0%B3%D0%B4%D0%B0%D0%BD",
            "geo:0,0?q=Kalemegdan",
        )) {
            assertEquals(link, Category.READING, rated(link, target = "com.google.android.apps.maps"))
        }
        // Review 6b-links I1: data in characters that the fold drops, or in a parameter's name.
        for (link in listOf(
            "https://evil.example/s?q=Kalemegdan+%F0%9D%90%80%F0%9D%90%A7%F0%9D%90%9A",
            "https://evil.example/s?q=Kalemegdan+%F0%9D%9F%8E%F0%9D%9F%94%F0%9D%9F%8E",
            "https://evil.example/s?q=Kalemegdan+%E2%81%B0%E2%81%B6%E2%81%B0",
            "https://evil.example/s?q=Kalemegdan+)%5E)!%40%23",
            "https://evil.example/s?q=Kalemegdan%CC%81%CC%82%CD%AF",
            "https://evil.example/s?anapetrovic=Kalemegdan",
            "https://evil.example/s?k=Kalemegdan",
        )) {
            assertEquals(link, Category.SENDING, rated(link))
        }
        // Review 6b-links M1: a messenger's host with a trailing dot or in capitals, and a link that a messenger's app opens.
        for (link in listOf("https://t.me./share/url?url=kestrel&text=hovering", "https://wa.me./?text=kestrel+hovering", "https://API.WHATSAPP.COM./send?text=kestrel")) {
            assertEquals(link, Category.SENDING, rated(link))
        }
        for (target in listOf("org.telegram.messenger", "org.telegram.messenger.web", "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip")) {
            assertEquals(target, Category.SENDING, rated("https://chat.example/share?text=kestrel+hovering", target))
            assertEquals(target, Category.SENDING, rated("geo:0,0?q=Kalemegdan", target))
        }
    }
}
