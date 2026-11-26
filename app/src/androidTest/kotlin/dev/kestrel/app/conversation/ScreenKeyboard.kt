package dev.kestrel.app.conversation

import android.view.View
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.conversation.screen.FIELD_TAG
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule

/**
 * The keyboard over the conversation screen in the device tests. The screen's window resizes with the keyboard, so the
 * field's row and the goal's buttons move with each frame of the keyboard's animation, which runs on Android's clock while
 * the test's clock moves Compose's frames, also between the down and the up of a test's tap: the up can then land beside
 * the button that the down hit, which takes no click. [settle] waits until the keyboard shows exactly while the field has
 * focus and none of its animations runs, before a test taps a button of the screen. While [rule] runs, which a test
 * applies outside its compose rule, each screen gets a watch on its window as it is made, before its keyboard can move,
 * and loses it as it is destroyed.
 */
internal object ScreenKeyboard {
    private val watches = HashMap<ConversationActivity, Watch>()

    private val follow = ActivityLifecycleCallback { activity, stage ->
        if (activity !is ConversationActivity) return@ActivityLifecycleCallback
        when (stage) {
            Stage.CREATED -> synchronized(watches) { watches[activity] = Watch(activity.window.decorView) }
            Stage.DESTROYED -> synchronized(watches) { watches.remove(activity) }
            else -> Unit
        }
    }

    /** Watches every conversation screen that is made while a test runs; it must be outside the test's compose rule. */
    val rule: TestRule = object : ExternalResource() {
        override fun before() = ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(follow)

        override fun after() {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(follow)
            synchronized(watches) { watches.clear() }
        }
    }

    /** Waits until the screen's keyboard shows exactly while its field has focus and none of its animations runs. */
    fun settle(compose: AndroidComposeTestRule<*, ConversationActivity>) {
        compose.waitUntil(SETTLE_MILLIS) {
            val focused = compose.onAllNodesWithTag(FIELD_TAG).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true }
            val activity = compose.activity
            val watch = synchronized(watches) { watches[activity] }
            checkNotNull(watch) { "The screen has no keyboard watch: the test applies ScreenKeyboard.rule outside its compose rule" }.settled(focused)
        }
        compose.waitForIdle()
    }


    // Notes the keyboard's animations on the window's root, from their preparation, in which the window's insets change
    // already, to their end; the screen's own views still get every animation after it.
    private class Watch(private val root: View) : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        private val running = mutableSetOf<WindowInsetsAnimationCompat>()

        init {
            ViewCompat.setWindowInsetsAnimationCallback(root, this)
        }

        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            if (keyboard(animation)) synchronized(running) { running += animation }
        }

        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>): WindowInsetsCompat = insets

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            synchronized(running) { running -= animation }
        }

        fun settled(shown: Boolean): Boolean {
            var visible: Boolean? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime())
            }
            return synchronized(running) { running.isEmpty() } && visible == shown
        }

        private fun keyboard(animation: WindowInsetsAnimationCompat) = (animation.typeMask and WindowInsetsCompat.Type.ime()) != 0
    }

    private const val SETTLE_MILLIS = 10_000L
}
