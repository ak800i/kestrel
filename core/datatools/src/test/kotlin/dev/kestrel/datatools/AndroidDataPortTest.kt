package dev.kestrel.datatools

import dev.kestrel.agent.port.DataResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidDataPortTest {
    @Test
    fun shareMediaRefusesNoItemsAndItemsThatFindMediaNeverGaveWithAFixedSentence() {
        val refused = DataResult.Failed(DataPortRules.NO_ITEMS)

        assertEquals(refused, DataPortRules.shareRefusal(emptyList(), emptyList()))
        assertEquals(refused, DataPortRules.shareRefusal(listOf("m1", "m9"), listOf("m9")))
        assertNull(DataPortRules.shareRefusal(listOf("m1", "m2"), emptyList()))
    }

    @Test
    fun aReadThatAndroidRefusesAsksForTheToolsPermissionsAndACancellationPassesThrough() = runTest {
        val refused: suspend () -> DataResult = { throw SecurityException("revoked") }

        assertEquals(DataResult.NeedsPermission(listOf("android.permission.READ_SMS")), DataPortRules.guarded(listOf("android.permission.READ_SMS"), refused))
        assertEquals(DataResult.Failed(DataPortRules.REFUSED), DataPortRules.guarded(emptyList(), refused))
        assertEquals(DataResult.Ok(emptyList()), DataPortRules.guarded(listOf("android.permission.READ_SMS")) { DataResult.Ok(emptyList()) })
        assertThrows(CancellationException::class.java) {
            runBlocking { DataPortRules.guarded(emptyList()) { throw CancellationException("stopped") } }
        }
    }

    @Test
    fun theTasksMediaTableIsForgottenOnlyOnceNoToolCallRuns() = runTest {
        var forgotten = 0
        val afterCalls = AfterCalls { forgotten++ }

        afterCalls.request()
        assertEquals(1, forgotten)

        val release = CompletableDeferred<Unit>()
        val call = async { afterCalls.call { release.await(); "done" } }
        yield()
        afterCalls.request()
        assertEquals(1, forgotten)
        release.complete(Unit)
        assertEquals("done", call.await())
        assertEquals(2, forgotten)

        val stopped = launch { afterCalls.call { CompletableDeferred<Unit>().await() } }
        yield()
        afterCalls.request()
        assertEquals(2, forgotten)
        stopped.cancel()
        stopped.join()
        assertEquals(3, forgotten)

        afterCalls.call { }
        assertEquals(3, forgotten)
    }
}
