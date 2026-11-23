package dev.kestrel.eval.run

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.Hooks

/**
 * The live gate (design D1 of sub-project 6), after [Preflight] turned test mode on: the default profile must be a
 * Copilot profile with the provider stand-in off; then the device's live session (hook agent-live) opens, in which a
 * task sent with `live` may call the owner's provider. Its restore puts back the state that it read, off normally and
 * on when scripts\a71-agent.ps1's sitting 2 opened it, and runs before test mode's own restore, so that a live session is
 * never open without test mode.
 */
class LiveGate(private val hooks: Hooks, private val restores: Restores, private val serial: String) {
    fun open() {
        val provider = hooks.providerStandin()
        if (provider["profile"] != "copilot") {
            throw Refusal("--live needs a Copilot profile as the default profile on $serial; its default profile is ${provider["profile"] ?: "unknown"}.")
        }
        if (provider["standin"] != "off") throw Refusal("--live needs the provider stand-in off on $serial: scripts\\hook.ps1 -Serial $serial provider-standin -On false.")
        val before = hooks.agentLive()["live"] == "on"
        restores.push("the live session on $serial ${if (before) "open" else "closed"} again", "Run: scripts\\hook.ps1 -Serial $serial agent-live -On $before") {
            val answer = try {
                hooks.agentLive(before)
            } catch (e: HookLost) {
                hooks.agentLive()
            }
            check(answer["live"] == if (before) "on" else "off") { "the live session is ${answer["live"]}" }
        }
        val answer = try {
            hooks.agentLive(true)
        } catch (e: HookLost) {
            hooks.agentLive()
        }
        if (answer["live"] != "on") throw Refusal("The live session on $serial did not open.")
    }
}
