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
        val winners = aliases.map { project -> async { workspace.acquire(project, "acquire-${project.id}") } }.awaitAll().filterNotNull()
        assertEquals(1, winners.size)
        val owner = winners.single()
        assertFailsWith<IllegalArgumentException> { workspace.release(owner.copy(ownerId = first.id)) }
        assertNull(workspace.acquire(first, "intruder"), "Another identity cannot release the active writer")
        val isolated = first.copy(id = "isolated", path = "/another/folder")
        val isolatedLease = assertNotNull(workspace.acquire(isolated, "isolated"))
        workspace.release(owner)
        val next = assertNotNull(workspace.acquire(first, "next"))
        workspace.release(owner)
        assertNull(workspace.acquire(first.copy(id = "late-intruder"), "late-intruder"), "Old cleanup cannot release the next grant")
        assertNull(workspace.acquire(isolated.copy(id = "intruder"), "isolated-intruder"))
        workspace.release(next)
        workspace.release(isolatedLease)
    }
}
