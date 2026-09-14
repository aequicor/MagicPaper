package io.aequicor.magicpaper.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PlanningWorkspaceOwnershipTest {
    @Test fun concurrentAliasesHaveExactlyOneWriterAndOnlyTheOwnerCanReleaseIt() = runTest {
        val workspace = LocalPlanningWorkspace()
        val first = CodingProject("one", "First", "/same/folder", 1)
        val aliases = (1..12).map { first.copy(id = "project-$it", path = if (it % 2 == 0) "/same/folder/" else "/same/folder") }
        val winners = aliases.map { project -> async { project to workspace.acquire(project) } }.awaitAll().filter { it.second }
        assertEquals(1, winners.size)
        val owner = winners.single().first
        workspace.release(first)
        assertFalse(workspace.acquire(first), "Another identity cannot release the active writer")
        val isolated = first.copy(id = "isolated", path = "/another/folder")
        assertTrue(workspace.acquire(isolated))
        workspace.release(owner)
        assertTrue(workspace.acquire(first))
        assertFalse(workspace.acquire(isolated.copy(id = "intruder")))
        workspace.release(first)
        workspace.release(isolated)
    }
}
