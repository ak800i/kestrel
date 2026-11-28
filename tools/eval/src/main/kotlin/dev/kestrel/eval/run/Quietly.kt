package dev.kestrel.eval.run

import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.HookSilent

/** The hook's answer, or null when Kestrel did not answer, refused, or adb lost the answer. */
internal fun <T> quietly(call: () -> T): T? = try {
    call()
} catch (e: HookError) {
    null
} catch (e: HookSilent) {
    null
} catch (e: HookLost) {
    null
} catch (e: DeviceCommandFailed) {
    null
}

/**
 * The hook's answer, or null only when no answer came: the call was lost ([HookLost]) or adb failed ([DeviceCommandFailed]).
 * A wait reads null as "not yet" and asks again. Every answer that was given stops the wait with its own message: Kestrel's
 * error ([HookError]), no hooks ([HookSilent]) and a refused caller ([dev.kestrel.eval.hook.HookCallerRefused]).
 */
internal fun <T> unlessLost(call: () -> T): T? = try {
    call()
} catch (e: HookLost) {
    null
} catch (e: DeviceCommandFailed) {
    null
}