package io.aequicor.magicpaper.data.llm

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class CodexCodingPermissionsTest {
    @Test fun cacheIsWritableButNetworkStillRequiresReview() = withHome { home ->
        val project = home.resolve("project")
        val policy = CodexCodingPermissions(project, home, null)
        assertEquals(listOf(project.toString(), home.resolve(".gradle").toString()), policy.writableRoots)
        assertEquals(JsonPrimitive(false), policy.sandboxPolicy()["networkAccess"])
        assertEquals(policy.sandboxPolicy()["writableRoots"], policy.threadConfig()["sandbox_workspace_write.writable_roots"])
        val approvals = buildJsonObject { with(policy) { approvals() } }
        assertEquals(JsonPrimitive("on-request"), approvals["approvalPolicy"])
        assertEquals(JsonPrimitive("auto_review"), approvals["approvalsReviewer"])
    }

    @Test fun customGradleCacheIsReused() = withHome { home ->
        val cache = home.resolve("caches/gradle")
        assertContains(CodexCodingPermissions(home.resolve("project"), home, cache.toString()).writableRoots, cache.toString())
    }

    @Test fun broadCacheOverridesCannotGrantHomeOrDisk() = withHome { home ->
        val project = home.resolve("project")
        for (cache in listOf(home, home.parent, home.root)) {
            assertEquals(listOf(project.toString()), CodexCodingPermissions(project, home, cache.toString()).writableRoots)
        }
        Files.createSymbolicLink(home.resolve(".gradle"), home)
        assertEquals(listOf(project.toString()), CodexCodingPermissions(project, home, null).writableRoots)
    }

    private fun withHome(test: (java.nio.file.Path) -> Unit) {
        val home = Files.createTempDirectory("coding-permissions-").toRealPath()
        try { test(home) } finally {
            Files.deleteIfExists(home.resolve(".gradle"))
            Files.deleteIfExists(home)
        }
    }
}
