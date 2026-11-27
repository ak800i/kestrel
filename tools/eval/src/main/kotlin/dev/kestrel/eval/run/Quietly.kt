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
