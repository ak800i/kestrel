package dev.kestrel.app.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildCommitTest {
    @Test
    fun theAssetsCommitAndDirtyFlagAreAnsweredAndAnythingElseIsUnknown() {
        val commit = "0123456789abcdef0123456789abcdef01234567"
        assertEquals("commit=$commit dirty=0", BuildCommit.answer("$commit 0\n"))
        assertEquals("commit=$commit dirty=1", BuildCommit.answer("$commit 1"))
        for (asset in listOf(null, "", "unknown 1", commit, "$commit 2", "${commit}0 0")) {
            assertEquals("$asset", "commit=unknown dirty=unknown", BuildCommit.answer(asset))
        }
    }
}
