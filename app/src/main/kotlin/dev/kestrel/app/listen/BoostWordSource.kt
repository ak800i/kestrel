package dev.kestrel.app.listen

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** An app's name as the launcher shows it, and whether the app came with the phone ([system]). */
internal data class AppLabel(val label: String, val system: Boolean) {
    // An app's name says what the owner uses (design spec, section 13.5).
    override fun toString() = "AppLabel(${label.length} characters, system=$system)"
}

/** Reads the names of the apps on the phone. */
internal fun interface AppLabels {
    fun read(): List<AppLabel>
}

/**
 * The labels of the launcher activities, in the order in which the package manager lists them. An app with two launcher
 * activities gives two labels; [BoostWordSource] drops a repeated name. An activity counts as a system app's when its
 * [ApplicationInfo] has [ApplicationInfo.FLAG_SYSTEM], or [ApplicationInfo.FLAG_UPDATED_SYSTEM_APP] for a system app
 * that an update replaced. Since Android 11, Kestrel sees the other apps' launcher activities only
 * because its manifest declares the launcher's intent in `<queries>` (design spec, section 13.6); Android shows a few
 * packages, such as the Settings app, to every app. It asks the package manager, so call it off the main thread.
 */
internal class LauncherAppLabels(private val context: Context) : AppLabels {
    override fun read(): List<AppLabel> {
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0)).map {
            AppLabel(it.loadLabel(packages).toString(), system = it.activityInfo.applicationInfo.flags and SYSTEM != 0)
        }
    }

    private companion object {
        const val SYSTEM = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}

/**
 * The boost words of a voice session (design spec, section 8.3): "Kestrel" first, then the names of the apps that the
 * owner installed, and then those of the system apps, each group in the order in which [labels] reads them. sherpa-onnx
 * gets at most 100 phrases, so the cap drops system apps first: their common names, such as "Phone" or "Camera", need
 * no boost, while the owner's apps have the rare names that the recogniser misses, such as "Revolut". Sub-project 3
 * adds the labels on the screen and the command vocabulary, and sub-project 5 the owner's aliases and contact names. A
 * name with fewer than [SHORTEST] letters or digits, such as "X", is left out, because so short a boost would pull many
 * words towards it, and a repeated name counts once, ignoring case. When the names cannot be read, [report] gets the
 * error, which it must log by its classes only (section 13.5), and the words are "Kestrel" alone. Never log a boost
 * word or an app's name.
 */
internal class BoostWordSource(private val labels: AppLabels, private val report: (String, Throwable) -> Unit) {
    /** Reads the names of the apps on [Dispatchers.IO]. A voice session asks once, when it opens. */
    suspend fun words(): List<String> {
        val apps = try {
            withContext(Dispatchers.IO) { labels.read() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Reading the names of the apps", e)
            emptyList()
        }
        // The owner's apps first; filter and filterNot keep the package manager's order within each group.
        val names = (apps.filterNot { it.system } + apps.filter { it.system })
            .map { it.label.trim() }
            .filter { name -> name.count { it.isLetterOrDigit() } >= SHORTEST }
        return (listOf(NAME) + names).distinctBy { it.lowercase() }
    }

    companion object {
        const val NAME = "Kestrel"
        const val SHORTEST = 3
    }
}

/**
 * Opens a voice session with [open], which gets the boost words of [source]. The words are read while [open] loads the
 * engines: [open] must ask for them only after its loads, as `VoiceSession.open` does. When the coroutine that opens
 * the session is cancelled after [open] returned, the session is closed before the cancellation goes on, so that no
 * engine stays loaded.
 */
internal suspend fun openWithBoostWords(
    source: BoostWordSource,
    open: suspend (boostWords: suspend () -> List<String>) -> Session,
): Session {
    var opened: Session? = null
    try {
        return coroutineScope {
            // words() throws only a cancellation or an Error, so a failed read of the names cannot fail this scope; a
            // failed load cancels the read.
            val words = async { source.words() }
            open { words.await() }.also { opened = it }
        }
    } catch (e: CancellationException) {
        // coroutineScope drops the value of its block when its job was cancelled while the block ran.
        withContext(NonCancellable) {
            try {
                opened?.close()
            } catch (closing: Exception) {
                e.addSuppressed(closing)
            }
        }
        throw e
    }
}
