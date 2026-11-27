package dev.kestrel.screen.read

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * How long Kestrel may still wait for a screen that keeps changing, in all the waits of one command together (design
 * spec, sections 9.2 and 9.3). [ScreenChangeTracker.awaitQuiet] takes the budget from the coroutine context:
 * [withQuietBudget] gives a command's run one budget, and a wait outside any command gets a budget of its own. Only a
 * wait that ends without quiet takes from the budget, and only the time that it waited beyond its floor: a wait after an
 * action always lasts its own quiet time from the action, for the app to react, and a wait that ends because the screen
 * went quiet costs nothing. So on a screen that never goes quiet, such as a stopwatch, a video or SystemUI's shade while it
 * sets its date again and again, a command waits [MILLIS] in all beyond those floors; once the budget is spent, its waits
 * for a snapshot end at once, each wait after an action lasts only its floor, and the command takes the screen as it is.
 * A command whose screen settles each time never spends it.
 */
class QuietBudget(millis: Long = MILLIS) : AbstractCoroutineContextElement(QuietBudget) {
    private val left = AtomicLong(millis)

    /** The time that the waits may still take; never below 0. */
    val remainingMillis: Long get() = left.get()

    /** Takes [millis] that a wait spent on a screen that did not go quiet. Waits side by side each take their own time. */
    internal fun spend(millis: Long) {
        left.updateAndGet { maxOf(0L, it - millis) }
    }

    companion object Key : CoroutineContext.Key<QuietBudget> {
        /** The time that one command, or one wait outside a command, may wait for a screen that keeps changing. */
        const val MILLIS = 3_000L
    }
}

/**
 * Runs [block], one command's run, with one [QuietBudget] for all its waits for a quiet screen. A run inside another
 * keeps the outer run's budget, so that nothing can give a command more time than its own budget.
 */
suspend fun <T> withQuietBudget(block: suspend CoroutineScope.() -> T): T {
    val budget = currentCoroutineContext()[QuietBudget] ?: QuietBudget()
    return withContext(budget, block)
}
