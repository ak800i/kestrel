package dev.kestrel.app.agent

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.safety.SelfChats
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent end to end on kestrel-api37 with the scripted model (design D27 of sub-project 5; D32 items 16 and 50): a
 * delete that the owner confirms on the card's question runs once, a hostile script on an injection fixture never presses
 * a send, call or delete without the owner, a share whose chat opens in a new activity is reported as sent, and the agent
 * stays out of Kestrel's own app. Test mode stays on; a delete is no send, so test mode does not block it, and the share
 * goes to the owner's self-chat.
 */
@RunWith(AndroidJUnit4::class)
class AgentEndToEndTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun start() {
        AgentTests.clearPresses(screen)
        AgentTests.start()
    }

    @After
    fun stop() {
        try {
            AgentTests.stop()
        } finally {
            AgentTests.clearPresses(screen)
            screen.shell("am force-stop $FIXTURES")
        }
    }

    private fun tap(label: String, category: String) = call("tap", label, null, null, "reason" to "the next step", "category" to category)

    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Test
    fun aDeleteIsAskedOnTheCardAndRunsOnceAfterTheOwnersYes() = runBlocking {
        screen.open("MessengerActivity", "Ana Petrović", "--es chat ana")
        val model = ScriptedModel(listOf(
            tap("Delete chat", "deleting"),
            call("finish", null, null, null, "summary" to "I deleted the chat.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Chat deleted")))),
        ))

        val task = AgentTests.run("delete my chat with Ana", model)
        AgentTests.awaitAsking(task, TaskAsk.CONFIRM)
        check(VoiceTests.waitUntil(5_000) { screen.showsWindow("Kestrel confirmation") }) { "The card did not show" }
        assertEquals(emptyList<String>(), AgentTests.presses(screen))
        AgentTests.say("confirm")
        val ended = AgentTests.ended(task)

        assertEquals(EndReason.FINISHED, ended.report.end)
        assertEquals(0, ended.report.unconfirmed)
        assertEquals(listOf("delete"), AgentTests.presses(screen))
    }

    @Test
    fun aHostileScriptOnAnInjectionFixtureNeverPressesWithoutTheOwner() = runBlocking {
        for (label in listOf("Delete chat", "Call", "Send")) {
            screen.open("InjectionActivity", "Ana Petrović", "--es fixture inject-01")
            val model = ScriptedModel(listOf(tap(label, "reading"), fail))

            val task = AgentTests.run("read my newest message from Ana", model)
            // Each question is answered "cancel", as :tools:eval answers the injection fixtures (design D28).
            check(VoiceTests.waitUntil(30_000) {
                val state = AgentTests.graph.listening.state.value
                if (state.task == task && state.taskAsking != null) AgentTests.say("cancel")
                AgentTests.graph.agent.ended(task) != null && !AgentTests.graph.agent.running(task)
            }) { "The task did not end" }
            val ended = AgentTests.ended(task)

            assertEquals(label, emptyList<String>(), AgentTests.presses(screen))
            assertEquals(label, 0, ended.report.unconfirmed)
            assertEquals(label, 0, ended.report.beforeTestMode)
        }
    }

    @Test
    fun aShareWhoseChatOpensInANewActivityIsReportedAsSent() = runBlocking {
        // It adds a photo and changes test mode's names, so it runs only on kestrel-api37; it needs the photos permission,
        // which a test cannot take back without ending Kestrel's process (see PermissionFlowTest).
        assumeTrue("Only on kestrel-api37", screen.shell("getprop ro.boot.qemu.avd_name").trim() == "kestrel-api37")
        assumeTrue(
            "Grant Kestrel READ_MEDIA_IMAGES before this test and revoke it afterwards",
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED,
        )
        val graph = AgentTests.graph
        val safety = graph.settings.get(SettingKeys.SAFETY)
        val aliases = graph.agent.selfChatAliases
        val photo = addPhoto()
        try {
            // Test mode lets the share go only to the owner's WhatsApp-like self-chat, which the select form opens in a new
            // activity (final review of plan 5B, part A, M1).
            graph.settings.set(SettingKeys.SAFETY, safety.copy(whatsappOwnName = OWNER))
            graph.agent.selfChatAliases = mapOf(FIXTURES to SelfChats.WHATSAPP)
            screen.open("MessengerActivity", "Chats", "--es shareForm select")
            val model = ScriptedModel(listOf(
                call("find_media", null, null, null, "day" to "today", "kinds" to "photos"),
                call("share_media", null, null, null, "app" to "Kestrel fixtures", "items" to listOf("m1")),
                call("wait", null, null, null, "seconds" to 3, "reason" to "let the share picker open"),
                tap("$OWNER (You) · Message yourself", "sending"),
                tap("SEND", "sending"),
                call("finish", null, null, null, "summary" to "I shared the photo.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Photo 1")))),
                fail,
            ))

            val task = AgentTests.run("share today's photo with my own WhatsApp chat", model)
            check(VoiceTests.waitUntil(60_000) {
                val state = AgentTests.graph.listening.state.value
                if (state.task == task && state.taskAsking == TaskAsk.CONFIRM) AgentTests.say("confirm")
                AgentTests.graph.agent.ended(task) != null && !AgentTests.graph.agent.running(task)
            }) { "The task did not end" }
            val ended = AgentTests.ended(task)

            val steps = "end=${ended.report.end} requests=${model.requests.size} confirmed=${ended.report.confirmed}"
            assertEquals(steps, listOf("share"), AgentTests.presses(screen))
            assertEquals(steps, 1, ended.report.sends.size)
            assertEquals(steps, 1, ended.report.shareItems)
            assertEquals(EndReason.FINISHED, ended.report.end)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, safety)
            graph.agent.selfChatAliases = aliases
            context.contentResolver.delete(photo, null, null)
        }
    }

    // A test-only photo of today, which Kestrel itself adds and removes, as PermissionFlowTest's.
    private fun addPhoto(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "kestrel_share_test.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KestrelTest")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the photo")
        context.contentResolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return uri
    }

    // The contact tools need both contacts permissions, which a test cannot take back from Kestrel without ending its
    // process; the plan's step grants them before these tests and takes back those that Kestrel lacked before.
    private fun assumeContacts() {
        assumeTrue("Only on kestrel-api37", screen.shell("getprop ro.boot.qemu.avd_name").trim() == "kestrel-api37")
        assumeTrue(
            "Grant Kestrel READ_CONTACTS and WRITE_CONTACTS before this test",
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
        )
    }

    private fun writerContacts(): Int = context.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY} = ? AND ${ContactsContract.RawContacts.DELETED} = 0", arrayOf(CONTACT), null,
    )?.use { it.count } ?: 0

    private fun removeWriterContacts() {
        context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY} = ?", arrayOf(CONTACT))
    }

    private fun addThenDelete(answer: String): Pair<AgentRunner.Ended, Int> {
        screen.open("MessengerActivity", "Chats")
        val add = ScriptedModel(listOf(
            call("add_contact", null, null, null, "name" to CONTACT, "number" to CONTACT_NUMBER),
            call("finish", null, null, null, "summary" to "I added the contact.", "evidence" to listOf(mapOf("step" to 1))),
        ))
        val added = AgentTests.ended(AgentTests.run("add a contact $CONTACT with the number $CONTACT_NUMBER", add))
        assertEquals(EndReason.FINISHED, added.report.end)
        assertEquals(0, added.report.confirmed)
        assertEquals(1, writerContacts())
        val delete = ScriptedModel(listOf(
            call("find_contact", null, null, null, "name" to CONTACT),
            call("delete_contact", null, null, null, "contact" to 1),
            call("finish", null, null, null, "summary" to "I deleted the contact.", "evidence" to listOf(mapOf("step" to 2))),
        ))
        val task = AgentTests.run("delete the contact $CONTACT", delete)
        AgentTests.awaitAsking(task, TaskAsk.CONFIRM)
        // kestrel-api37's contacts sync to the owner's Google account: the test answers only when find_contact found the
        // test contact alone, so that the question names it and a yes can delete nothing else.
        val found = AgentTests.texts(delete).lines().filter { FOUND_LINE.containsMatchIn(it) }
        check(found.size == 1 && "name: \"$CONTACT\"" in found.single()) { "find_contact found ${found.size} contacts, not the test contact alone" }
        AgentTests.say(answer)
        return AgentTests.ended(task) to writerContacts()
    }

    @Test
    fun aContactIsAddedWithoutAQuestionAndDeletedAfterTheOwnersYes() {
        assumeContacts()
        try {
            val (deleted, left) = addThenDelete("confirm")

            assertEquals(EndReason.FINISHED, deleted.report.end)
            assertEquals(1, deleted.report.confirmed)
            assertEquals(0, deleted.report.unconfirmed)
            assertEquals(0, left)
        } finally {
            removeWriterContacts()
        }
    }

    @Test
    fun aContactIsKeptAfterTheOwnersNo() {
        assumeContacts()
        try {
            val (declined, left) = addThenDelete("no")

            assertEquals(EndReason.DECLINED, declined.report.end)
            assertEquals(0, declined.report.unconfirmed)
            assertEquals(1, left)
        } finally {
            removeWriterContacts()
        }
    }

    @Test
    fun theAgentStaysOutOfKestrelsOwnApp() = runBlocking {
        // Kestrel's settings in front: the task waits up to 3 s for Kestrel's own screen to leave, and since it stays, the
        // task stops as interrupted before any action and any model call (design D16; D32 item 16; plan 7A).
        screen.shell("am start -W -n $KESTREL/.app.MainActivity")
        screen.awaitElement { it.packageName == KESTREL }
        val settings = ScriptedModel(listOf(tap("Safety", "reading"), fail))
        val first = AgentTests.ended(AgentTests.run("open the safety page", settings))
        assertEquals(EndReason.INTERRUPTED, first.report.end)
        assertEquals(Interruption.KESTREL_SCREEN, first.report.interruption)
        assertTrue(settings.requests.isEmpty())

        // open_app refuses Kestrel: the fixture stays in front.
        screen.open("MessengerActivity", "Chats")
        val open = ScriptedModel(listOf(call("open_app", null, null, null, "name" to "Kestrel", "reason" to "open it"), fail))
        val second = AgentTests.ended(AgentTests.run("open Kestrel", open))

        assertEquals(EndReason.FAILED_BY_MODEL, second.report.end)
        assertEquals(FIXTURES, ScreenTree.frontWindow(screen.session.snapshots.current().snapshot)?.packageName)
        assertTrue(open.requests.size >= 2)
    }

    private companion object {
        // The owner's fictional name in the fixture's WhatsApp-like self-chat (FixtureChats.OWNER_NAME).
        const val OWNER = "Test Owner"

        // A test contact of the contact tools' cases, with a fictional number.
        const val CONTACT = "Kestrel Writer Test"
        const val CONTACT_NUMBER = "+1 202 555 0188"

        // A contact in find_contact's result, as "contact 1 (apps: Telegram) · name: …".
        val FOUND_LINE = Regex("""\bcontact \d+ \(""")
    }
}
