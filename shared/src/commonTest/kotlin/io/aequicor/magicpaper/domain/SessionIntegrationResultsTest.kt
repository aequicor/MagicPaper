package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class SessionIntegrationResultsTest {
    private val own = SessionCodingWorkspace(1, "parent-run",
        StageAttempt("parent-attempt", "parent", StageAssignment("", ""), path = "/parent-own",
            baseCommit = "grandparent-base", resultCommit = "own-sha", verificationSnapshot = "own-files"),
        SessionCodingWorkspacePhase.CAPTURED, PlanWorkspace("/parent-work", "/parent-own", "grandparent-base", git = true),
        sourceSnapshot = "grandparent-files", resultSnapshot = "own-files", sourcePath = "/grandparent")
    private fun integration(id: String = "combined", snapshot: String = "own-files") = SessionIntegration(
        SessionIntegrationRequest(id, "organism", "parent", 1, listOf("grandchild-result"), listOf(listOf("check")), "/parent-own", snapshot),
        SessionIntegrationPhase.VERIFIED, PlanWorkspace("/integration-work", "/$id", "parent-intermediate-base", git = true),
        mergedResultIds = listOf("grandchild-result"), checkResults = listOf(SessionIntegrationCheck(listOf("check"), 0, "All tests passed")),
        commitSha = "$id-sha", snapshot = "$id-files")
    private fun organism(): SessionOrganism {
        val root = SessionNode("root", SessionKind.ZYGOTE, "Root", generation = 1, mode = CodingInteractionMode.CODE,
            observed = SessionObservedState.RUNNING, remainingTokens = 10_000)
        val parent = SessionNode("parent", SessionKind.SESSION, "Parent", "root", generation = 1,
            mode = CodingInteractionMode.CODE, observed = SessionObservedState.COMPLETED,
            task = SessionTask("Implement nested feature", "root", "Verified feature", "grandparent-files"), workspace = own)
        return SessionOrganism("organism", "project", "root", "immunity", 1,
            sessions = mapOf("root" to root, "parent" to parent), integrations = mapOf("combined" to integration()),
            audit = listOf(SessionAuditEvent("combined-checkpoint-5", "parent", "INTEGRATION_VERIFIED", setOf("parent"), "Checks passed", 5)))
    }
    private fun result() = SessionResult("parent-result", "parent", 1, "root", "Parent and grandchild implementation",
        artifacts = listOf("/combined"), sourceVersion = "combined-files", commitSha = "combined-sha",
        checks = integration().resultChecks(), integrationId = "combined")

    @Test fun completedNestedResultIncludesCombinedCommitAndKeepsOriginalGrandparentBase() {
        val aggregate = organism()
        val (integrationId, workspace) = assertNotNull(aggregate.completedResultWorkspace("parent", 1))
        assertEquals("combined", integrationId)
        assertEquals("combined-sha", workspace.attempt.resultCommit)
        assertEquals("/combined", workspace.attempt.path)
        assertEquals("combined-files", workspace.resultSnapshot)
        assertEquals("grandparent-base", workspace.attempt.baseCommit)
        assertEquals("/grandparent", workspace.sourcePath)
        assertEquals("grandparent-files", workspace.sourceSnapshot)
        assertEquals(own, aggregate.sessions.getValue("parent").workspace) // Actual writer ownership never moves.
        assertEquals(workspace, aggregate.resultWorkspace(result()))
    }

    @Test fun laterOwnEditsAndAnotherGenerationInvalidateIntegratedResultSelection() {
        val aggregate = organism()
        val changed = aggregate.copy(sessions = aggregate.sessions + ("parent" to aggregate.sessions.getValue("parent").copy(
            workspace = own.copy(resultSnapshot = "later-own-edits", attempt = own.attempt.copy(resultCommit = "later-sha")))))
        val selected = assertNotNull(changed.completedResultWorkspace("parent", 1))
        assertNull(selected.first)
        assertEquals("later-sha", selected.second.attempt.resultCommit)
        assertNull(changed.resultWorkspace(result()))
        assertNull(aggregate.completedResultWorkspace("parent", 2))
        assertNull(aggregate.resultWorkspace(result().copy(generation = 2)))
        assertNull(aggregate.resultWorkspace(result().copy(commitSha = "invented-sha")))
    }

    @Test fun savedResultReferenceRemainsBoundToItsIntegrationWhenANewerOneExists() {
        val aggregate = organism()
        val next = integration("second")
        val multiple = aggregate.copy(integrations = aggregate.integrations + ("second" to next),
            audit = aggregate.audit + SessionAuditEvent("second-checkpoint-9", "parent", "INTEGRATION_VERIFIED", setOf("parent"), "Checks passed", 9))
        assertEquals("second", multiple.completedResultWorkspace("parent", 1)?.first)
        assertEquals("combined-sha", multiple.resultWorkspace(result())?.attempt?.resultCommit)
        val unrelated = aggregate.copy(integrations = mapOf("combined" to integration().copy(request = integration().request.copy(actorSessionId = "sibling"))))
        assertNull(unrelated.resultWorkspace(result()))
    }

    @Test fun storeRejectsForgedBindingAndAdmitsTheCombinedResultForItsGrandparent() = runTest {
        val storage = InMemoryKeyValueStore()
        val aggregate = organism()
        storage.write("session-organism-${aggregate.id}", Json.encodeToString(SessionOrganism.serializer(), aggregate))
        val store = SessionOrganismStore(storage) { 10 }
        assertFailsWith<IllegalArgumentException> { store.recordResult(aggregate.id, result().copy(commitSha = "own-sha")) }
        assertFailsWith<IllegalArgumentException> { store.recordResult(aggregate.id, result().copy(checks = listOf("Invented success"))) }
        assertEquals(aggregate, store.get(aggregate.id))
        store.recordResult(aggregate.id, result())
        val actor = SessionAuthority("project", aggregate.id, "root", 1, CodingInteractionMode.CODE)
        val accepted = store.command(actor, "accept", OrganismCommand(OrganismAction.REVIEW_RESULT, "parent", reason = "Checked combined artifact",
            resultId = "parent-result", accepted = true, sourceVersion = "combined-files", checks = listOf("All tests passed")))
        val received = accepted.results.single()
        val effective = assertNotNull(accepted.resultWorkspace(received))
        assertEquals("combined-sha", effective.attempt.resultCommit)
        assertNotEquals(accepted.sessions.getValue("parent").workspace?.attempt?.resultCommit, effective.attempt.resultCommit)
        val admitted = store.admitIntegration(actor, SessionIntegrationRequest("grandparent-integration", aggregate.id, "root", 1,
            listOf(received.id), listOf(listOf("check")), "/grandparent", "grandparent-files"), "fingerprint")
        assertTrue(admitted.second)
        assertEquals(listOf("parent-result"), admitted.first.integrations.getValue("grandparent-integration").request.resultIds)
    }
}
