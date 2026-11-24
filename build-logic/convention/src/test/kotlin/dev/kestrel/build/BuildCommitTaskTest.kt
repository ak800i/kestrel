package dev.kestrel.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCommitTaskTest {
    @Test
    fun onlyAChangeOutsideTheToolTheDocumentationAndMarkdownMakesTheBuildDirty() {
        assertFalse(BuildCommitTask.dirty(""))
        assertFalse(BuildCommitTask.dirty(" M tools/eval/src/main/kotlin/A.kt\n?? docs/superpowers/plans/p.md\n M README.md\n"))
        assertTrue(BuildCommitTask.dirty(" M app/src/main/kotlin/dev/kestrel/app/MainActivity.kt\n"))
        assertTrue(BuildCommitTask.dirty("?? \"core/agent/src/main/kotlin/New File.kt\"\n"))
        // A file renamed out of the tool counts by its new path.
        assertTrue(BuildCommitTask.dirty("R  tools/eval/A.kt -> core/agent/A.kt\n"))
    }
}
