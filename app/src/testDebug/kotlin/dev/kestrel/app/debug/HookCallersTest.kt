package dev.kestrel.app.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule of the hooks' callers: adb's shell and root only. */
class HookCallersTest {
    @Test
    fun onlyAdbsShellAndRootMayCall() {
        assertTrue(HookCallers.allowed(2000))
        assertTrue(HookCallers.allowed(0))
    }

    @Test
    fun theSystemUidAndEveryAppAreRefused() {
        // 1000 is the system uid, which passes every permission check, DUMP's too; 10xxx are apps, Kestrel's own among them.
        for (uid in listOf(1000, 10_000, 10_187, 19_999, 1_010_187, 2001, 1999, -1)) assertFalse("$uid", HookCallers.allowed(uid))
    }
}
