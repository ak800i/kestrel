package dev.kestrel.agent.port

import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.LinkVerdict

/**
 * Something that Kestrel opens for the agent, judged as it will be sent (spec 12.1 and 12.5; plan 6B-basefix). Only
 * [Judge] makes one, and [ScreenAction.Launch], the only action that opens anything, takes nothing else, so every
 * `open_*` tool, every data tool that opens an app's screen and the return to the task's app pass the same check.
 * [target] is the app that opens, which the check judged: the app that the intent names, or else the one app that Android
 * resolves it to, to which the sent intent is then pinned. It is null only for an intent that names no app and that no
 * app or several apps take, so that Android shows its chooser (spec 12.1).
 */
class CheckedLaunch private constructor(val target: String?, val opens: Opens) {
    sealed interface Opens {
        /** [packageName] opens as its launcher icon opens it. */
        data class App(val packageName: String) : Opens {
            override fun toString(): String = "App(package=${packageName.length} chars)"
        }

        data class Activity(val intent: IntentSpec) : Opens
    }

    sealed interface Judgement {
        class Cleared(val launch: CheckedLaunch) : Judgement {
            override fun toString(): String = "Cleared($launch)"
        }

        /** The intent names an app that Android does not open it in now. */
        data object NotResolved : Judgement

        /** [packageName] is an app that the agent may not use: an agent-free app, Kestrel or Play services. */
        data class Closed(val packageName: String) : Judgement {
            override fun toString(): String = "Closed(package=${packageName.length} chars)"
        }

        /** LinkCheck refused the link with [reason], one of its fixed sentences. */
        data class LinkRefused(val reason: String) : Judgement {
            override fun toString(): String = "LinkRefused(reason=${reason.length} chars)"
        }
    }

    /**
     * The launch check: [closed] tells the apps that the agent may not use, and [linkCheck] is LinkCheck with the task's
     * rules, for the link of an intent that carries one.
     */
    internal class Judge(
        private val screen: ScreenPort,
        private val closed: (String?) -> Boolean,
        private val linkCheck: (link: String, kind: ActionKind, target: String?) -> LinkVerdict,
    ) {
        fun app(packageName: String): Judgement =
            if (closed(packageName)) Judgement.Closed(packageName) else Judgement.Cleared(CheckedLaunch(packageName, Opens.App(packageName)))

        /**
         * Resolves [spec] as it will be sent and judges the app that opens it: an app that the intent names must take it,
         * a link must pass LinkCheck as a link of [linkKind], and the app may not be closed. The intent is pinned to that app.
         */
        suspend fun intent(spec: IntentSpec, linkKind: ActionKind): Judgement {
            val named = spec.packageName
            val target = if (named != null) named.takeIf { it in screen.handlers(spec) } ?: return Judgement.NotResolved else screen.resolve(spec)
            val link = spec.data?.takeIf { spec.isLink }
            if (link != null) (linkCheck(link, linkKind, target) as? LinkVerdict.Refused)?.let { return Judgement.LinkRefused(it.reason) }
            if (target != null && closed(target)) return Judgement.Closed(target)
            return Judgement.Cleared(CheckedLaunch(target, Opens.Activity(spec.copy(packageName = target))))
        }
    }

    override fun toString(): String = "CheckedLaunch(target=${target != null}, opens=$opens)"
}
