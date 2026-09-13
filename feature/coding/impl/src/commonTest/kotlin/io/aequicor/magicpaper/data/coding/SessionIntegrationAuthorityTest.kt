package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionIntegrationAuthorityTest {
    private class Fixture {
        val storage = InMemoryKeyValueStore()
        val store = SessionOrganismStore(storage) { 1_000 }
        val scope = SessionAuthority("project", "root", "root", 0, CodingInteractionMode.CODE)
        val request = SessionIntegrationRequest("integration", "root", "root", 0, listOf("result"),
            listOf(listOf("./gradlew", "check")), "/source", "source")
        suspend fun initialize() {
            store.adopt("project", CodingSession("root", "project", "Root", 1), emptyList())
            store.command(scope, "child", OrganismCommand(OrganismAction.CREATE, name = "Child", tokens = 1_000,
                task = SessionTask("Implement", "root", "Checks pass", "source")))
            val child = store.beginRun("root", "session-child")
            store.recordWorkspace("root", child.id, child.generation, SessionCodingWorkspace(child.generation, "run",
                StageAttempt("attempt", child.id, StageAssignment("", ""), path = "/child", baseCommit = "base", resultCommit = "child-sha"),
                SessionCodingWorkspacePhase.CAPTURED, sourceSnapshot = "source", resultSnapshot = "child-snapshot", sourcePath = "/source"))
            store.observe("root", child.id, child.generation, SessionObservedState.COMPLETED)
            store.recordResult("root", SessionResult("result", child.id, child.generation, "root", "Implemented", sourceVersion = "child-snapshot", commitSha = "child-sha"))
            store.command(scope, "review", OrganismCommand(OrganismAction.REVIEW_RESULT, child.id, resultId = "result", accepted = true,
                reason = "Verified", sourceVersion = "child-snapshot", checks = listOf("Tests pass")))
        }
    }

    @Test fun immutableIntentRejectsChangedArgumentsForeignProjectAndReadOnlyAuthority() = runTest {
        val f = Fixture(); f.initialize()
        assertFailsWith<IllegalArgumentException> { f.store.admitIntegration(f.scope.copy(mode = CodingInteractionMode.RESEARCH), f.request, "digest") }
        assertTrue(f.store.admitIntegration(f.scope, f.request, "digest").second)
        assertFalse(f.store.admitIntegration(f.scope, f.request, "digest").second)
        assertFailsWith<IllegalArgumentException> { f.store.admitIntegration(f.scope, f.request.copy(checks = listOf(listOf("different"))), "other-digest") }
        assertFailsWith<IllegalArgumentException> { f.store.admitIntegration(f.scope.copy(projectId = "foreign"), f.request, "digest") }
    }

    @Test fun stopCannotPretendThatAnUnfinishedIntegrationSucceededAndRecoveryDoesNotReplayIt() = runTest {
        val f = Fixture(); f.initialize()
        val (saved, _) = f.store.admitIntegration(f.scope, f.request, "digest")
        f.store.observe("root", "root", 0, SessionObservedState.STOPPED)
        f.store.finishStop("root", saved.sessions.keys - setOfNotNull(saved.immunityId))
        assertEquals(SessionOperationState.ACCEPTED, f.store.get("root").operations.getValue("integration").state)
        val restarted = SessionOrganismStore(f.storage) { 1_001 }
        val recovered = restarted.recover("root")
        assertEquals(SessionIntegrationPhase.UNKNOWN, recovered.integrations.getValue("integration").phase)
        assertEquals(SessionOperationState.UNKNOWN, recovered.operations.getValue("integration").state)
        assertEquals(SessionDesiredState.QUARANTINE, recovered.sessions.getValue("root").desired)
        assertEquals(SessionObservedState.UNKNOWN, recovered.sessions.getValue("root").observed)
        assertEquals(f.request, recovered.integrations.getValue("integration").request)
        assertFalse(restarted.admitIntegration(f.scope, f.request, "digest").second, "A duplicate only returns its recorded unknown outcome")
        assertFailsWith<IllegalArgumentException> { restarted.admitIntegration(f.scope, f.request.copy(id = "new"), "new-digest") }
    }

    @Test fun lateCheckpointCannotPublishAfterOwnerGenerationRevocation() = runTest {
        val f = Fixture(); f.initialize()
        val (saved, _) = f.store.admitIntegration(f.scope, f.request, "digest")
        f.store.changeRootMode("root", "root", CodingInteractionMode.RESEARCH)
        assertFailsWith<IllegalArgumentException> { f.store.checkpointIntegration("root", saved.integrations.getValue("integration").copy(phase = SessionIntegrationPhase.PREPARING)) }
        assertEquals(SessionIntegrationPhase.INTENT, f.store.get("root").integrations.getValue("integration").phase)
    }

    @Test fun hostCheckpointCannotSkipVerificationAndMasksActualCheckOutput() = runTest {
        val f = Fixture(); f.initialize(); f.store.knownSecrets = { setOf("configured-secret") }
        assertFailsWith<IllegalArgumentException> { f.store.admitIntegration(f.scope, f.request.copy(checks = listOf(listOf("check", "configured-secret"))), "unsafe") }
        var record = f.store.admitIntegration(f.scope, f.request, "digest").first.integrations.getValue("integration")
        assertFailsWith<IllegalArgumentException> { f.store.checkpointIntegration("root", record.copy(phase = SessionIntegrationPhase.VERIFIED, commitSha = "invented")) }
        record = record.copy(phase = SessionIntegrationPhase.PREPARING); f.store.checkpointIntegration("root", record)
        record = record.copy(phase = SessionIntegrationPhase.MERGING, workspace = PlanWorkspace("/managed", "/integrated", "base", git = true))
        f.store.checkpointIntegration("root", record)
        record = record.copy(phase = SessionIntegrationPhase.VERIFYING, mergedResultIds = listOf("result"), snapshot = "combined")
        f.store.checkpointIntegration("root", record)
        f.store.checkpointIntegration("root", record.copy(checkResults = listOf(SessionIntegrationCheck(f.request.checks.single(), 0, "configured-secret"))))
        assertFalse(f.storage.read("session-organism-root")!!.contains("configured-secret"))
        assertEquals(SessionOperationState.ACCEPTED, f.store.get("root").operations.getValue("integration").state)
    }
}
