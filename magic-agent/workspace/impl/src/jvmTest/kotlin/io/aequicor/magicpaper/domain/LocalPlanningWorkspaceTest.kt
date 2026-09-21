package io.aequicor.magicpaper.domain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalPlanningWorkspaceTest {
    @Test fun canonicalAliasesShareOneLeaseAndIndependentPathsStayAvailable() = runTest {
        val source = Files.createTempDirectory("local-planning-source-").toFile()
        val other = Files.createTempDirectory("local-planning-other-").toFile()
        val project = CodingProject("owner", "Source", source.path, 1)
        val port = LocalPlanningWorkspace()
        val lease = assertNotNull(port.acquire(project, "request"))
        assertEquals(source.canonicalPath, lease.canonicalPath)
        assertEquals(project.id, lease.ownerId)
        assertEquals("request", lease.requestId)
        assertNull(port.acquire(project, "request"))
        assertNull(port.acquire(project.copy(id = "alias", path = File(source, ".").path), "alias-request"))
        assertEquals(project.id, port.holderOf(File(source, ".").path))
        val independent = assertNotNull(port.acquire(project.copy(id = "other", path = other.path), "other-request"))
        port.release(lease)
        assertNull(port.holderOf(source.path))
        assertEquals("other", port.holderOf(other.path))
        port.release(independent)
    }

    @Test fun oldReleaseCannotUnlockTheNextGenerationOfTheSameRequest() = runTest {
        val source = Files.createTempDirectory("local-planning-generation-").toFile()
        val project = CodingProject("owner", "Source", source.path, 1)
        val port = LocalPlanningWorkspace()
        val old = assertNotNull(port.acquire(project, "same-request"))
        port.release(old)
        val current = assertNotNull(port.acquire(project, "same-request"))
        assertNotEquals(old.token, current.token)
        port.release(old)
        assertEquals(project.id, port.holderOf(source.path))
        assertNull(port.acquire(project.copy(id = "other"), "other-request"))
        port.release(current)
        port.release(current)
        port.release(assertNotNull(port.acquire(project.copy(id = "other"), "other-request")))
    }

    @Test fun activeTokenRejectsChangedOwnerRequestOrPath() = runTest {
        val source = Files.createTempDirectory("local-planning-identity-").toFile()
        val project = CodingProject("owner", "Source", source.path, 1)
        val port = LocalPlanningWorkspace()
        val lease = assertNotNull(port.acquire(project, "request"))
        for (changed in listOf(
            lease.copy(ownerId = "different-owner"),
            lease.copy(requestId = "different-request"),
            lease.copy(canonicalPath = File(source, "other").path),
        )) assertFailsWith<IllegalArgumentException> { port.release(changed) }
        assertEquals(project.id, port.holderOf(source.path))
        assertNull(port.acquire(project, "next-request"))
        port.release(lease)
    }

    @Test fun operationsNeedTheExactLiveLease() = runTest {
        val source = Files.createTempDirectory("local-planning-operation-").toFile()
        val project = CodingProject("owner", "Source", source.path, 1)
        val port = LocalPlanningWorkspace()
        val old = assertNotNull(port.acquire(project, "same-request"))
        assertEquals(project.path, port.prepare(project, "run", WorkspaceOperation(old, "prepare")).integrationPath)
        port.release(old)
        val current = assertNotNull(port.acquire(project, "same-request"))
        for (invalid in listOf(old, current.copy(requestId = "other"), current.copy(ownerId = "other"))) {
            assertFailsWith<IllegalArgumentException> { port.prepare(project, "run", WorkspaceOperation(invalid, "prepare")) }
        }
        assertFailsWith<IllegalArgumentException> { port.prepare(project, "run", WorkspaceOperation(current, "")) }
        assertEquals(current.ownerId, port.holderOf(project.path))
        port.release(current)
    }

    @Test fun acquireRequiresAnExplicitOwnerAndRequest() = runTest {
        val source = Files.createTempDirectory("local-planning-required-identity-").toFile()
        val project = CodingProject("owner", "Source", source.path, 1)
        val port = LocalPlanningWorkspace()
        assertFailsWith<IllegalArgumentException> { port.acquire(project, " ") }
        assertFailsWith<IllegalArgumentException> { port.acquire(project.copy(id = ""), "request") }
        assertNull(port.holderOf(source.path))
    }
}
