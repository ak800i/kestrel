package dev.kestrel.commands.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactMatcherTest {
    private fun contact(id: Long, name: String, vararg numbers: PhoneEntry, starred: Boolean = false, nickname: String? = null) =
        Contact(id, listOfNotNull(name, nickname), numbers.toList(), starred)

    private val mobile = PhoneEntry("064 123 4567", "mobile", superPrimary = false)
    private val work = PhoneEntry("011 234 5678", "work", superPrimary = false)
    private val ana = contact(1, "Ana Petrović", mobile, work)
    private val anaJ = contact(2, "Ana Jovanović", mobile)
    private val anja = contact(3, "Anja Marković", mobile)
    private val djordje = contact(4, "Đorđe Ilić", mobile, nickname = "Đole")
    private val marko = contact(5, "Marko", mobile)
    private val contacts = listOf(ana, anaJ, anja, djordje, marko)

    @Test
    fun aFullNameAFirstNameAndANicknameFindTheirContact() {
        assertEquals(ContactMatch.One(ana), ContactMatcher.find("Ana Petrovic", contacts))
        assertEquals(ContactMatch.One(anja), ContactMatcher.find("Anja", contacts))
        assertEquals(ContactMatch.One(djordje), ContactMatcher.find("Djordje Ilic", contacts))
        assertEquals(ContactMatch.One(djordje), ContactMatcher.find("Đole", contacts))
        assertEquals(ContactMatch.One(marko), ContactMatcher.find("Marko", contacts))
    }

    @Test
    fun aSharedFirstNameAsksAndTheStarredContactComesFirst() {
        assertEquals(ContactMatch.Several(listOf(anaJ, ana)), ContactMatcher.find("Ana", contacts))
        val starred = ana.copy(starred = true)
        assertEquals(ContactMatch.Several(listOf(starred, anaJ)), ContactMatcher.find("Ana", listOf(starred, anaJ, anja)))
    }

    @Test
    fun moreThanFourContactsAreTooManyAndAStrangerIsNone() {
        val many = (1L..6L).map { contact(it, "Ana Number$it", mobile) }

        assertEquals(ContactMatch.TooMany(6), ContactMatcher.find("Ana", many))
        assertEquals(ContactMatch.None, ContactMatcher.find("Nikola", contacts))

    }

    @Test
    fun theNumberIsTheKindThatWasSaidElseTheDefaultElseTheOnlyOne() {
        assertEquals(NumberChoice.One(work), ContactMatcher.number(ana, "work"))
        assertEquals(NumberChoice.One(mobile), ContactMatcher.number(ana, "cell"))
        assertEquals(NumberChoice.None, ContactMatcher.number(ana, "home"))
        assertEquals(NumberChoice.Several(listOf(mobile, work)), ContactMatcher.number(ana, null))
        val defaultWork = work.copy(superPrimary = true)
        assertEquals(NumberChoice.One(defaultWork), ContactMatcher.number(ana.copy(numbers = listOf(mobile, defaultWork)), null))
        assertEquals(NumberChoice.One(mobile), ContactMatcher.number(anaJ, null))
        val gym = PhoneEntry("011 555 0101", "Gym", superPrimary = false)
        assertEquals(NumberChoice.One(gym), ContactMatcher.number(ana.copy(numbers = listOf(mobile, gym)), "gym"))
    }

    @Test
    fun aSingleNearMissIsOfferedAsAConfirmation() {
        val maria = contact(7, "Maria Ilić", mobile)

        assertEquals(ContactMatch.Several(listOf(maria)), ContactMatcher.find("Marija", listOf(maria, marko)))
    }

    @Test
    fun numbersThatDifferOnlyInFormattingCountOnce() {
        val same = contact(6, "Dan", mobile, PhoneEntry("064-123-4567", "home", superPrimary = false))

        assertEquals(NumberChoice.One(mobile), ContactMatcher.number(same, null))
        // The kind comes first, so a home number that repeats the mobile one is still the home number.
        assertEquals(NumberChoice.One(same.numbers[1]), ContactMatcher.number(same, "home"))
        // The copy that is the default stays.
        val defaultCopy = PhoneEntry("064-123-4567", "home", superPrimary = true)
        assertEquals(NumberChoice.One(defaultCopy), ContactMatcher.number(same.copy(numbers = listOf(mobile, defaultCopy, work)), null))
    }

    @Test
    fun tooManyCountsEveryCloseContactOnTheFuzzyPath() {
        val many = (1L..25L).map { contact(it, "Ana Number$it", mobile) }

        assertEquals(ContactMatch.TooMany(25), ContactMatcher.find("Ana Numbr", many))
    }
}
