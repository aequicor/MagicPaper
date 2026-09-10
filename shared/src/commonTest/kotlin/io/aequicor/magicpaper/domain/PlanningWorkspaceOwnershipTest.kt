package io.aequicor.magicpaper.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PlanningWorkspaceOwnershipTest {
    @Test fun concurrentAcquisitionAllowsOneWriterAndPreservesOwnerIdentity() = runTest {
        val workspace = LocalPlanningWorkspace()
        val first = CodingProject("one", "First", "/same/folder", 1)
        val aliases = (1..12).map { first.copy(id = "project-$it", path = if (it % 2 == 0) "/same/folder/" else "/same/folder") }
        val winners = aliases.map { project -> async { project to workspace.acquire(project) } }.awaitAll().filter { it.second }
        assertEquals(1, winners.size)
        val owner = winners.single().first
        val unrelated = aliases.first { it.id != owner.id }
        workspace.release(unrelated)
        assertFalse(workspace.acquire(unrelated))
        workspace.release(owner)
        assertTrue(workspace.acquire(unrelated))
        assertTrue(workspace.acquire(first.copy(id = "other", path = "/another/folder")))
    }
}
