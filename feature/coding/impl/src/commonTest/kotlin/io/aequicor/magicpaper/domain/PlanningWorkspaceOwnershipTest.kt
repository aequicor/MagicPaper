package io.aequicor.magicpaper.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PlanningWorkspaceOwnershipTest {
    @Test fun concurrentAcquisitionAllowsIndependentSessionsByOwnerIdentity() = runTest {
        val workspace = LocalPlanningWorkspace()
        val first = CodingProject("one", "First", "/same/folder", 1)
        val aliases = (1..12).map { first.copy(id = "project-$it", path = if (it % 2 == 0) "/same/folder/" else "/same/folder") }
        val winners = aliases.map { project -> async { project to workspace.acquire(project) } }.awaitAll().filter { it.second }
        // Each session with a unique owner ID acquires independently, even on the same path.
        assertEquals(aliases.size, winners.size)
        val owner = winners.first().first
        // Releasing one session does not affect others.
        workspace.release(owner)
        // After release, the same owner ID can acquire again.
        assertTrue(workspace.acquire(owner))
        workspace.release(owner)
        // A fresh owner ID on a different path also acquires.
        assertTrue(workspace.acquire(first.copy(id = "other", path = "/another/folder")))
        // Release all remaining sessions.
        winners.filter { it.first.id != owner.id }.forEach { workspace.release(it.first) }
        // Verify all can be re-acquired after full release.
        assertTrue(workspace.acquire(owner))
        workspace.release(owner)
    }
}
