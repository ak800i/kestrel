package dev.kestrel.app.agent

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kestrel's own permission request during a task (design D12 and D15 of sub-project 5): a task that needs the photos
 * permission, which Kestrel lacks as after a fresh install, hands over to Android's dialog through PermissionActivity; the
 * hand-over ends a few seconds after the answer, and the task goes on to the share screen. A request before it that ends
 * without an answer ("Kestrel, continue") closes Android's dialog and PermissionActivity within 5 s. The test puts back
 * the flags that the dialog set on the media permissions. A test cannot take a permission back without ending Kestrel's
 * process, so Task 19's step revokes the media permissions before the run and after it; the test skips itself when they
 * are granted.
 */
@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var photo: android.net.Uri? = null
    // Null until @Before has passed its assumption: JUnit runs @After also for a skipped test, which must change nothing.
    private var flagsBefore: Map<String, Set<String>>? = null

    @Before
    fun start() {
        assumeTrue(
            "Revoke Kestrel's media permissions before this test, as plan 5B's Task 19 does",
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED,
        )
        flagsBefore = flags()
        AgentTests.clearPresses(screen)
        AgentTests.start()
    }

    @After
    fun stop() {
        val before = flagsBefore ?: return
        try {
            AgentTests.stop()
        } finally {
            photo?.let { context.contentResolver.delete(it, null, null) }
            AgentTests.clearPresses(screen)
            screen.shell("am force-stop $FIXTURES")
            putBackFlags(before)
        }
    }

    // The flags that Android's dialog sets on Kestrel's media permissions, such as user-set, by their names in
    // `dumpsys package`; Task 19's step takes the grants back.
    private fun flags(): Map<String, Set<String>> {
        val dump = screen.shell("dumpsys package $KESTREL")
        return MEDIA.associateWith { permission ->
            Regex("""${Regex.escape(permission)}: granted=\w+, flags=\[([^\]]*)]""").find(dump)?.groupValues?.get(1)
                ?.split('|')?.map { it.trim() }?.filter { it in FLAGS }?.toSet().orEmpty()
        }
    }

    private fun putBackFlags(flagsBefore: Map<String, Set<String>>) {
        val now = flags()
        for (permission in MEDIA) {
            val before = flagsBefore[permission].orEmpty()
            val after = now[permission].orEmpty()
            for (flag in after - before) screen.shell("pm clear-permission-flags $KESTREL $permission ${FLAGS.getValue(flag)}")
            for (flag in before - after) screen.shell("pm set-permission-flags $KESTREL $permission ${FLAGS.getValue(flag)}")
        }
        check(flags() == flagsBefore) { "The media permissions' flags were not put back" }
    }

    // Android's dialog as the owner sees it; the test answers it by touch through UiAutomation.
    private fun awaitAllow(): AccessibilityNodeInfo {
        var allow: AccessibilityNodeInfo? = null
        check(VoiceTests.waitUntil(20_000) {
            val root = screen.automation.rootInActiveWindow
            allow = ALLOW_IDS.firstNotNullOfOrNull { id -> root?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() }
            allow != null
        }) { "Android's permission dialog did not show" }
        return allow!!
    }

    // Whether Android's dialog or Kestrel's PermissionActivity is still among Android's activities.
    private fun showsPermissionActivities(): Boolean {
        val activities = screen.shell("dumpsys activity activities")
        return "GrantPermissionsActivity" in activities || ".app.agent.PermissionActivity" in activities
    }
    // A test-only photo of today, which Kestrel itself adds and removes; without a date taken, find_media dates it by when
    // it was added (design D12).
    private fun addPhoto() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "kestrel_permission_test.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KestrelTest")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the photo")
        photo = uri
        context.contentResolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test
    fun aTaskThatNeedsThePhotosAsksAndroidAndReachesTheShareScreen() = runBlocking {
        addPhoto()
        screen.open("MessengerActivity", "Chats", "--es shareForm tap")

        // A request that ends without an answer ("Kestrel, continue") closes Android's dialog and PermissionActivity, so
        // that no stale dialog stays and the next request shows its own.
        val unanswered = ScriptedModel(listOf(
            call("find_media", null, null, null, "day" to "today", "kinds" to "photos"),
            call("fail", null, null, null, "reason" to "The test stops without the photos."),
        ))
        val first = AgentTests.run("find today's photos", unanswered)
        awaitAllow()
        AgentTests.say("Kestrel, continue")
        check(VoiceTests.waitUntil(5_000) { !showsPermissionActivities() }) { "Android's dialog or PermissionActivity stayed after the request ended" }
        AgentTests.ended(first)
        assertNotEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))

        val model = ScriptedModel(listOf(
            call("find_media", null, null, null, "day" to "today", "kinds" to "photos"),
            call("share_media", null, null, null, "app" to "Kestrel fixtures", "items" to listOf("m1")),
            call("fail", null, null, null, "reason" to "The test stops at the share screen."),
        ))

        val task = AgentTests.run("share today's photo with John", model)
        // Android's dialog, which the owner answers by touch: the test taps "Allow all" (or "Allow") through UiAutomation.
        assertTrue(awaitAllow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val answered = SystemClock.elapsedRealtime()
        check(VoiceTests.waitUntil(5_000) { AgentTests.graph.conversations.owner.handOverKind == null && !showsPermissionActivities() }) {
            "The request did not end within 5 s of the answer"
        }
        screen.awaitElement(timeoutMillis = 15_000) { it.packageName == FIXTURES && it.label == "Share with" }
        val ended = AgentTests.ended(task)

        assertTrue(SystemClock.elapsedRealtime() - answered < 15_000)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES))
        assertEquals(EndReason.FAILED_BY_MODEL, ended.report.end)
        assertEquals(emptyList<String>(), AgentTests.presses(screen))
    }

    private companion object {
        val ALLOW_IDS = listOf(
            "com.android.permissioncontroller:id/permission_allow_all_button",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.google.android.permissioncontroller:id/permission_allow_all_button",
            "com.google.android.permissioncontroller:id/permission_allow_button",
        )

        val MEDIA = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        // The flags of `dumpsys package` that a dialog's answer sets, with their names for `pm set-permission-flags`.
        val FLAGS = mapOf("USER_SET" to "user-set", "USER_FIXED" to "user-fixed")
    }
}