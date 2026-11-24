package dev.kestrel.app.agent

import android.content.pm.PackageManager
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.CheckedLaunch
import dev.kestrel.agent.port.Direction
import dev.kestrel.agent.port.GlobalKind
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.Press
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.ScreenPort
import dev.kestrel.datatools.IntentSpecs
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GesturePaths
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.PressPolicy
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The screen as the agent sees and moves it (design D1, D8, D11 and D32 items 27 and 29 of sub-project 5), through the
 * screen session that is connected when each step runs ([session]). Each action checks the element against what the
 * observation showed ([AgentScreenChecks.expected]) and presses as [Press] says; an uncertain press is reported as such.
 * Every launch is a `CheckedLaunch` of the agent's launch check: an app opens through `ScreenActions.openApp`, and an
 * intent through `openIntent` after `IntentSpecs.toIntent`; Android lets Kestrel's accessibility service start both
 * from the background. After every action, also a failed one, [acted] tells the call backstop (D13). While the
 * confirmation card shows, the port waits until it has gone before it reads or acts (D8). The numbers of look_closer go
 * through [showBadges] and [hideBadges], which plan 5C sets.
 */
internal class AppScreenPort(
    private val session: () -> ScreenSession?,
    private val packageManager: PackageManager,
    private val showBadges: suspend (ScreenSession, List<Badge>) -> Unit,
    private val hideBadges: suspend (ScreenSession) -> Unit,
    private val acted: () -> Unit,
) : ScreenPort {
    override suspend fun snapshot(): ScreenSnapshot? {
        val screen = session() ?: return null
        afterCard(screen)
        return screen.snapshots.current().snapshot
    }

    override suspend fun act(action: ScreenAction): ActionOutcome {
        val screen = session() ?: return ActionOutcome(done = false, reason = AgentTexts.NO_SERVICE)
        afterCard(screen)
        val result = try {
            perform(screen, action)
        } finally {
            acted()
        }
        return outcome(result)
    }

    override suspend fun resolve(intent: IntentSpec): String? = withContext(Dispatchers.IO) { IntentSpecs.resolve(packageManager, intent) }

    override suspend fun handlers(intent: IntentSpec): Set<String> = withContext(Dispatchers.IO) { IntentSpecs.handlers(packageManager, intent) }

    override suspend fun showBadges(badges: List<Badge>) {
        session()?.let { showBadges(it, badges) }
    }

    override suspend fun hideBadges() {
        session()?.let { hideBadges(it) }
    }

    override suspend fun waitForFront(packageName: String?, millis: Long, before: String?): Boolean {
        val screen = session() ?: return false
        return withTimeoutOrNull(millis) {
            while (!AgentScreenChecks.inFront(screen.snapshots.current().snapshot, packageName, before)) delay(POLL_MILLIS)
            true
        } ?: false
    }

    private suspend fun perform(screen: ScreenSession, action: ScreenAction): ActionResult {
        val actions = screen.actions
        return when (action) {
            is ScreenAction.Tap -> actions.tap(action.key, AgentScreenChecks.expected(action.expect), policy(action.press), action.guard)
            is ScreenAction.LongPress -> actions.longPress(action.key, AgentScreenChecks.expected(action.expect), policy(action.press), action.guard)
            is ScreenAction.TapAt -> actions.tapAt(action.x, action.y)
            is ScreenAction.SetText -> actions.setText(action.key, action.text, AgentScreenChecks.expected(action.expect))
            is ScreenAction.Scroll -> actions.scroll(direction(action.direction), action.key, action.expect?.let(AgentScreenChecks::expected))
            is ScreenAction.Swipe -> swipe(screen, action)
            is ScreenAction.SetSlider -> actions.setSlider(action.key, action.percent, AgentScreenChecks.expected(action.expect))
            is ScreenAction.Global -> actions.global(if (action.kind == GlobalKind.BACK) GlobalAction.BACK else GlobalAction.HOME)
            is ScreenAction.Launch -> when (val opens = action.launch.opens) {
                is CheckedLaunch.Opens.App -> actions.openApp(opens.packageName)
                is CheckedLaunch.Opens.Activity -> actions.openIntent(IntentSpecs.toIntent(opens.intent))
            }
        }
    }

    // One complete stroke (D32 item 29) from the centre of the element or of the app window, a third of the screen in
    // the finger's direction, as the instant command "swipe up" goes.
    private suspend fun swipe(screen: ScreenSession, action: ScreenAction.Swipe): ActionResult {
        val snapshot = screen.snapshots.current().snapshot
        val element = action.key?.let { snapshot.element(it) ?: return ActionResult.CHANGED }
        if (element != null && action.expect?.let { !AgentScreenChecks.expected(it)(element) } == true) return ActionResult.CHANGED
        val bounds = screen.screenBounds
        val area = AgentScreenChecks.swipeArea(snapshot, action.key, bounds) ?: return ActionResult.CHANGED
        val line = GesturePaths.limit(bounds, area.centerX, area.centerY, direction(action.direction)) ?: return OFF_SCREEN
        return screen.actions.swipe(line.fromX, line.fromY, line.toX, line.toY)
    }

    // Design D8: after a confirmation, the card's window must be gone before the screen is read or acted on.
    private suspend fun afterCard(screen: ScreenSession) {
        if (!screen.cardShowing) return
        withTimeoutOrNull(CARD_MILLIS) { while (screen.cardShowing) delay(POLL_MILLIS) }
    }

    override fun toString(): String = "AppScreenPort(connected=${session() != null})"

    companion object {
        const val POLL_MILLIS = 100L
        const val CARD_MILLIS = 2_000L
        private val OFF_SCREEN = ActionResult.Failed("The point is outside the screen.")

        fun policy(press: Press): PressPolicy = when (press) {
            Press.ONCE -> PressPolicy.ONCE
            Press.VERIFIED -> PressPolicy.VERIFIED
        }

        fun direction(direction: Direction): ScrollDirection = when (direction) {
            Direction.UP -> ScrollDirection.UP
            Direction.DOWN -> ScrollDirection.DOWN
            Direction.LEFT -> ScrollDirection.LEFT
            Direction.RIGHT -> ScrollDirection.RIGHT
        }

        /**
         * What the agent learns of an action: a failure with its fixed sentence, and an uncertain press as done and
         * uncertain, because plan 5A's `ScreenSteps` reads `uncertain` only on an outcome that is done. It then judges the
         * press by the screen, and the next press of a send needs a new yes (design D32 item 29 of sub-project 5).
         */
        fun outcome(result: ActionResult): ActionOutcome = when (result) {
            is ActionResult.Done -> ActionOutcome(done = true, method = result.method.name.lowercase())
            ActionResult.UNCERTAIN -> ActionOutcome(done = true, uncertain = true)
            is ActionResult.Failed -> ActionOutcome(done = false, reason = result.reason)
        }
    }
}
