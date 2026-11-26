package dev.kestrel.app.conversation.screen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.MainActivity
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.app.settings.SetupChecklist
import dev.kestrel.app.settings.SetupState
import kotlinx.coroutines.launch

/**
 * Kestrel's conversation screen (design 10.1 of sub-project 7), the launcher's activity (D20). It holds no conversation
 * state of its own: [ConversationScreenViewModel] keeps the screen's model, and [ConversationApp] draws it and gives the
 * text channel the owner's typed words and buttons. Typed words count as the owner's only from here (design 12.2): the
 * window drops a touch that passed through another app's window, from Android 14 only accessibility tools may act on it,
 * and no intent carries words: "Reply…" names only a question's number ([EXTRA_QUESTION]), which puts the keyboard on the
 * field only while that question is open.
 */
class ConversationActivity : ComponentActivity() {
    private val graph by lazy { (application as KestrelApplication).graph }
    private val screen: ConversationScreenViewModel by viewModels { viewModelFactory { initializer { ConversationScreenViewModel(graph) } } }
    private var setupNeeded by mutableStateOf(false)
    private var replyTo by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ScreenGuard.guard(window.decorView)
        follow(intent)
        // A leave that the model asked for while no activity was there, as during a rotation, waits for this one.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { screen.leaves.collect { moveTaskToBack(true) } } }
        setContent {
            MaterialTheme {
                ConversationApp(screen, setupNeeded, replyTo, ::openSettings, ::openChecklist) { moveTaskToBack(true) }
            }
        }
    }

    // singleTask: the launcher, the notifications and "Reply…" bring this screen back with their intent.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        follow(intent)
    }

    override fun onResume() {
        super.onResume()
        screen.inFront(true)
        // The launcher opens this screen, so a grant of READ_PHONE_STATE made outside Kestrel starts the call watch here too.
        graph.phoneStateGrant.check()
        refreshSetup()
    }

    override fun onPause() {
        // A configuration change, such as a rotation, makes the activity again at once: the screen stays in front for its
        // model, which keeps waiting for the goal's service, and the card stays on the screen (review N3).
        if (!isChangingConfigurations) screen.inFront(false)
        super.onPause()
    }

    // Design 12.2: only a question's number, which is no command; any other extra and any data are ignored.
    private fun follow(intent: Intent) {
        replyTo = intent.getIntExtra(EXTRA_QUESTION, 0).takeIf { it > 0 }
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openChecklist() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, SettingsPage.CHECKLIST.name))
    }

    // The banner shows while a step of the setup checklist is to do or needs attention, as the settings' checklist reads it.
    private fun refreshSetup() {
        lifecycleScope.launch(graph.errors) {
            val coroutine = coroutineContext
            val states = SetupState.read(graph, applicationContext) { error -> graph.errors.handleException(coroutine, error) }
            setupNeeded = SetupChecklist.needsSetup(states)
        }
    }

    companion object {
        /** The number of the question that "Reply…" opened the screen for (design 10.3); the only extra that the screen reads. */
        const val EXTRA_QUESTION = "dev.kestrel.app.extra.QUESTION"

        /** "Reply…" on the overlay's card: the screen in front, with question [question] in focus and the keyboard up. */
        internal fun reply(context: Context, question: Int) {
            context.startActivity(
                Intent(context, ConversationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_QUESTION, question),
            )
        }
    }
}
