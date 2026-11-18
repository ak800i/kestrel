package dev.kestrel.app.commands

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.RequiresApi
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult

/** An app that the launcher shows, in the owner's profile or the work profile. Its text form never shows its name. */
internal data class LaunchableApp(val label: String, val component: ComponentName, val user: UserHandle, val work: Boolean) {
    override fun toString() = "LaunchableApp(label of ${label.length} characters, work=$work)"
}

/**
 * "Open X" and "open work X" (design spec, Appendix A; research r21). Kestrel matches X against the launcher's apps of
 * the owner's profile and, on Android 15 and later, of a work profile, and starts the match through the launcher API. On
 * Android 13 and 14 it cannot tell a work profile from Secure Folder or a clone profile, so it uses only the owner's
 * profile there.
 */
internal class AppCommands(environment: CommandEnvironment) {
    private val launcher: LauncherApps? = environment.context.getSystemService(LauncherApps::class.java)
    private val users: UserManager? = environment.context.getSystemService(UserManager::class.java)
    private val report = environment.report

    suspend fun open(screen: ScreenSession, command: PhoneCommand.OpenApp, conversation: CommandConversation): CommandResult {
        val launcher = launcher ?: return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.cantFindApp(command.name))
        val owner = Process.myUserHandle()
        val work = if (Build.VERSION.SDK_INT >= WORK_PROFILE_API) workProfile(launcher, owner) else null
        if (command.work && Build.VERSION.SDK_INT < WORK_PROFILE_API) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.WORK_APPS_TOO_OLD)
        }
        if (command.work && work == null) return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_WORK_PROFILE)
        val apps = when {
            command.work -> apps(launcher, checkNotNull(work), isWork = true)
            work == null -> apps(launcher, owner, isWork = false)
            else -> apps(launcher, owner, isWork = false) + apps(launcher, work, isWork = true)
        }
        val app = when (val picked = pick(command.name, apps, { it.label }, { !it.work })) {
            is Pick.One -> picked.item
            is Pick.Several -> {
                val names = picked.items.map { it.label }
                when (val answer = askAmong(conversation, PhoneCommandTexts.whichOne(names), names)) {
                    is Answer.Chosen -> picked.items[answer.index]
                    is Answer.Ends -> return answer.result
                }
            }
            Pick.None -> return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.cantFindApp(command.name))
        }
        // Android shows its own dialog to turn paused work apps on.
        val paused = app.work && users?.isQuietModeEnabled(app.user) == true
        return when (val result = screen.system.openActivity(app.component, app.user)) {
            is ActionResult.Done -> CommandResult(Outcome.DONE, say = PhoneCommandTexts.WORK_APPS_PAUSED.takeIf { paused }, repeatable = true)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
        }
    }

    private fun apps(launcher: LauncherApps, user: UserHandle, isWork: Boolean): List<LaunchableApp> = try {
        launcher.getActivityList(null, user).map { LaunchableApp(it.label.toString(), it.componentName, user, isWork) }
    } catch (e: SecurityException) {
        // Only the class, never a message (section 13.5).
        report("Listing the apps of a profile", e)
        emptyList()
    }

    // A profile whose type is the managed profile; never Secure Folder, a clone profile or the private space. A profile
    // that Android refuses to describe counts as no work profile.
    @RequiresApi(WORK_PROFILE_API)
    private fun workProfile(launcher: LauncherApps, owner: UserHandle): UserHandle? = launcher.profiles.firstOrNull { user ->
        user != owner && try {
            launcher.getLauncherUserInfo(user)?.userType == MANAGED_PROFILE
        } catch (e: SecurityException) {
            report("Reading a profile's type", e)
            false
        }
    }

    private companion object {
        // LauncherApps.getLauncherUserInfo and its user types arrived with Android 15.
        const val WORK_PROFILE_API = 35
        const val MANAGED_PROFILE = "android.os.usertype.profile.MANAGED"
    }
}

/** What the owner named: one item, several close ones for "Which one?", or none. */
internal sealed interface Pick<out T> {
    data class One<T>(val item: T) : Pick<T>

    data class Several<T>(val items: List<T>) : Pick<T>

    data object None : Pick<Nothing>
}

/** How many names "Which one?" says aloud at most. */
internal const val MAX_SPOKEN_CHOICES = 4

/**
 * The item whose [name] the owner said in [spoken], with the matcher's normal limits (design spec, section 9.6). Among
 * items with the same name, the [preferred] ones stand for the others, as an app of the owner's profile does for the same
 * app in the work profile; two preferred items with the same name both stay, for "Which one?". A single near miss gets
 * "Which one?" too, with its one name, and only its number or its name chooses it; any other answer, "yes" among them,
 * ends the command and is passed on as a new utterance.
 */
internal fun <T> pick(spoken: String, items: List<T>, name: (T) -> String, preferred: (T) -> Boolean): Pick<T> {
    val distinct = items.filter { Normalizer.normalize(name(it)).isNotEmpty() }
        .groupBy { Normalizer.normalize(name(it)) }
        .values.flatMap { same -> same.filter(preferred).ifEmpty { same } }
    val candidates = distinct.mapIndexed { index, item -> MatchCandidate(index.toString(), listOf(name(item))) }
    return when (val decision = LabelMatcher.decide(spoken, candidates, Strictness.NORMAL)) {
        is MatchDecision.Found -> Pick.One(distinct[decision.id.toInt()])
        is MatchDecision.Ambiguous -> Pick.Several(decision.ids.take(MAX_SPOKEN_CHOICES).map { distinct[it.toInt()] })
        MatchDecision.NotFound -> Pick.None
    }
}

/** How the answer to a spoken "Which one?" ended: with the index of the chosen name, or with the command's result. */
internal sealed interface Answer {
    data class Chosen(val index: Int) : Answer

    data class Ends(val result: CommandResult) : Answer
}

/**
 * Asks [question], whose [names] are numbered from 1, and reads the answer: a number or one of the names, after [fold],
 * such as "cell" for "mobile". An answer that is neither ends the command and is read as a new utterance; an answer that
 * names two of them ends it with "Say the number."; no answer cancels it.
 */
internal suspend fun askAmong(
    conversation: CommandConversation,
    question: String,
    names: List<String>,
    fold: (String) -> String = { it },
): Answer {
    val answer = conversation.ask(question) ?: return Answer.Ends(CommandResult(Outcome.CANCELLED))
    SpokenNumbers.answer(answer)?.let { number ->
        return if (number in 1..names.size) {
            Answer.Chosen(number - 1)
        } else {
            Answer.Ends(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.noChoice(number)))
        }
    }
    val candidates = names.mapIndexed { index, name -> MatchCandidate(index.toString(), listOf(name)) }
    return when (val found = LabelMatcher.decide(fold(answer), candidates, Strictness.NORMAL)) {
        is MatchDecision.Found -> Answer.Chosen(found.id.toInt())
        // Two offered items with the same name, such as two mobile numbers: only their numbers tell them apart.
        is MatchDecision.Ambiguous -> Answer.Ends(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.SAY_THE_NUMBER))
        MatchDecision.NotFound -> Answer.Ends(CommandResult(Outcome.CANCELLED, passOn = answer))
    }
}
