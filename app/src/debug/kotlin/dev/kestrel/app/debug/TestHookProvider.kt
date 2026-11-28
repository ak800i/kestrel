package dev.kestrel.app.debug

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import dev.kestrel.app.KestrelApplication
import dev.kestrel.privacy.logs.describeWithoutMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The test hooks' one transport, in debug builds only (design spec, section 16.1; plan 7G's amendment "The hooks'
 * transport"): `adb shell content call --uri content://dev.kestrel.hooks --method <COMMAND> --extra <name>:<type>:<value>
 * ...`, which `scripts/hook.ps1` and `:tools:eval` send. [call] runs [TestHooks] with the command and the extras and
 * returns its [HookReply].
 *
 * Why a provider's call and not a broadcast: Android delivers broadcasts to one app's process one at a time, so a hook
 * that answered inside its own broadcast held back every other broadcast to Kestrel until it answered, also the one that
 * it waited for, as `goal-control tap` waits for the broadcast of the goal notification's button. A call runs on one of
 * the binder threads of Kestrel's process and blocks only that thread, so Kestrel's broadcasts arrive while a hook waits.
 *
 * Who may call: Android checks the provider's permission DUMP (the manifest) only when a caller acquires the provider,
 * never on a call, and a holder could hand the provider on. So [call] checks its caller every time, by the rule of the
 * broadcast receiver that it replaces: the caller holds `android.permission.DUMP`, which in practice is adb's shell (uid
 * 2000) and root (uid 0), which holds every permission. Every app without DUMP is refused, and so is Kestrel's own
 * process, which does not hold DUMP and whose calls are no binder calls. A refusal is a SecurityException, which
 * `content call` prints as its error.
 *
 * Threads: Android publishes the provider before the application's onCreate builds the graph, so a call that starts
 * Kestrel's process can come first; the call reads the graph on the main thread, which runs posted work only after
 * onCreate. The hook then runs on Dispatchers.IO, as in the receiver before, and the hooks that need the main thread
 * switch to it, which the waiting binder thread does not hold. [TIMEOUT_MILLIS] bounds the whole call.
 *
 * The answer: a Bundle with exactly the keys [KEY_CODE] ([HookReply.OK] or [HookReply.FAILED]) and [KEY_TEXT].
 * `content call` prints it as `Result: Bundle[{code=0, text=<text>}]`, `code` first because Android's Bundle keeps its
 * keys in the order of their hashes, and the callers read the text as all that stands between `text=` and the last `}]`.
 */
class TestHookProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireContext()
        if (context.checkCallingPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Kestrel's test hooks answer only callers that hold android.permission.DUMP, such as adb shell")
        }
        val reply = runBlocking { answer(context.applicationContext, method, extras ?: Bundle()) }
        return Bundle().apply {
            putInt(KEY_CODE, reply.code)
            putString(KEY_TEXT, reply.text)
        }
    }

    private suspend fun answer(app: Context, command: String, extras: Bundle): HookReply = try {
        withTimeout(TIMEOUT_MILLIS) {
            val graph = withContext(Dispatchers.Main) { (app as KestrelApplication).graph }
            withContext(Dispatchers.IO) { TestHooks(graph, app).run(command, extras) }
        }
    } catch (error: Throwable) {
        // Errors too, such as a StackOverflowError: the caller gets the reason instead of a broken call.
        HookReply.failed("the command failed: ${error.describeWithoutMessages()}")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException(ONLY_CALL)

    override fun getType(uri: Uri): String? = throw UnsupportedOperationException(ONLY_CALL)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException(ONLY_CALL)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException(ONLY_CALL)

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(ONLY_CALL)

    companion object {
        const val AUTHORITY = "dev.kestrel.hooks"
        const val KEY_CODE = "code"
        const val KEY_TEXT = "text"

        // The longest hook, the spike's watch of at most 15 s, fits, and an action waits at most 3 s for the screen to settle.
        private const val TIMEOUT_MILLIS = 20_000L
        private const val ONLY_CALL = "Kestrel's test hooks answer only content call"
    }
}
