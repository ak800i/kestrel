package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
import org.junit.Test

class NamePickTest {
    private class App(val label: String, val work: Boolean)

    private val chrome = App("Chrome", work = false)
    private val workChrome = App("Chrome", work = true)
    private val gmail = App("Gmail", work = false)
    private val clock = App("Clock", work = false)
    private val photos = App("Photos", work = false)
    private val editor = App("Photo Editor", work = false)
    private val apps = listOf(workChrome, chrome, gmail, clock, photos, editor, App("Phone", work = false))

    private fun pick(spoken: String, items: List<App> = apps) = NamePick.pick(spoken, items, { it.label }, { !it.work })

    @Test
    fun theOwnersAppStandsForTheSameAppInTheWorkProfile() {
        assertEquals(Pick.One(gmail), pick("Gmail"))
        assertEquals(Pick.One(chrome), pick("chrome"))
        assertEquals(Pick.One(workChrome), pick("Chrome", listOf(workChrome, gmail)))
        assertEquals(Pick.None, pick("Quorbex Zyntal"))
    }

    @Test
    fun twoAppsOfTheOwnerWithTheSameNameAreBothOffered() {
        val otherPhone = App("Phone", work = false)
        val phone = App("Phone", work = false)

        assertEquals(Pick.Several(listOf(phone, otherPhone)), pick("Phone", listOf(phone, otherPhone, gmail)))
    }

    @Test
    fun closeNamesAreOfferedAndAtMostFourOfThem() {
        assertEquals(Pick.Several(listOf(photos, editor)), pick("Photo"))
        val many = (1..6).map { App("Notes $it", work = false) }
        assertEquals(NamePick.MAX_CHOICES, (pick("Notes", many) as Pick.Several).items.size)
    }
}
