package dev.kestrel.eval.run

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.Hooks

/**
 * The device's live session (hook agent-live) for a command that is not `--live` (design D2 of sub-project 6): a tool
 * that was ended hard, such as by `taskkill /F`, runs no shutdown hook and may have left the session on, in which a task
 * sent with `live` may call the owner's provider. Off is the safe state, so turning it off needs no restore.
 */
class LiveSession(private val hooks: Hooks, private val console: Console, private val serial: String) {
    fun ensureOff() {
        if (hooks.agentLive()["live"] != "on") return
        console.warn("The live session on $serial was on, as a tool that was ended hard can leave it; :tools:eval turns it off.")
        val answer = try {
            hooks.agentLive(false)
        } catch (e: HookLost) {
            hooks.agentLive()
        }
        if (answer["live"] != "off") throw Refusal("The live session on $serial is still on; run scripts\\hook.ps1 -Serial $serial agent-live -On false.")
    }
}
