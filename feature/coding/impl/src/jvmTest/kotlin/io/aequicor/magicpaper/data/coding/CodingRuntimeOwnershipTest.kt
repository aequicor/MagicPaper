package io.aequicor.magicpaper.data.coding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.test.*

class CodingRuntimeOwnershipTest {
    @Test fun cancellationRevokesPreflightAndBlocksReplacementUntilCleanupFinishes() {
        val owner = CodingRuntimeOwnership()
        val job = Job()
        val old = owner.begin("s", 1, job)
        owner.cancel("s")
        assertTrue(job.isCancelled)
        assertFailsWith<CancellationException> { owner.checkCurrent(old) }
        assertFailsWith<IllegalStateException> { owner.begin("s", 2, Job()) }
        owner.finish(old)
        val fresh = owner.begin("s", 2, Job())
        owner.finish(old)
        owner.checkCurrent(fresh)
        assertFailsWith<CancellationException> { owner.checkCurrent(old) }
        owner.finish(fresh)
        assertFailsWith<IllegalStateException> { owner.begin("s", 1, Job()) }
    }

    @Test fun stoppingOneScopeDoesNotCancelAnIndependentScopeButAppStopDoes() {
        val owner = CodingRuntimeOwnership()
        val a = owner.begin("a", 1, Job())
        val b = owner.begin("b", 1, Job())
        owner.cancel("a")
        assertTrue(a.job.isCancelled)
        assertTrue(b.job.isActive)
        owner.cancelAll()
        assertTrue(b.job.isCancelled)
    }
}
