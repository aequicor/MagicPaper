package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.BackendAgentCapability
import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NativeRuntimeDispatchTest {
    @Test fun recoveryRequiredKeepsItsExactEvidenceAndNeverPublishesFinished() = runBlocking {
        val root = Files.createTempDirectory("native-dispatch-recovery")
        try {
            val selected = RecordingRuntime(root.toString(), "partial output")
            val runtime = coordinator(selected, RecordingRuntime(root.toString(), "unused"))
            val profile = LlmProfile("profile", "Fixture", baseUrl = "http://fixture.invalid/v1", apiKey = "fixture", modelId = "fixture")
            val project = CodingProject("project", "Fixture", root.toString(), 1)
            val session = CodingSession("session", project.id, "Fixture", 1, engine = CodingEngine.PI)
            val proof = NativeRunRecoverySnapshot(listOf(NativeRunRecoveryItem(
                NativeRunRecoveryRef(CodingEngine.PI, session.id, "request", 0), NativeRunOutcome.UNKNOWN,
                NativeRunTermination.STOPPED, null)), false)
            val cause = IllegalStateException("Transport outcome is unknown")
            val required = NativeRunRecoveryRequired(proof, cause)
            selected.failure = required
            for (mode in listOf("code", "planning", "research")) {
                val observed = mutableListOf<CodingEvent>()
                val failure = assertFailsWith<NativeRunRecoveryRequired> {
                    (if (mode == "planning") runtime.runPlanning(project, session.copy(planningMode = true), "plan", profile)
                    else runtime.run(project, session.copy(researchMode = mode == "research"), "run", profile))
                        .onEach { delay(10) }.toList(observed)
                }
                assertSame(required, failure)
                assertSame(proof, failure.recovery)
                assertSame(cause, failure.cause)
                assertEquals(listOf<CodingEvent>(CodingEvent.Notice("partial output")), observed, mode)
            }
            // Both failure paths must release the coordinator lease; native admission remains its owner's decision.
            selected.failure = null
            assertEquals(1, runtime.run(project, session, "next", profile).toList().count { it is CodingEvent.Finished })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun collectorFailurePropagatesUnchangedWhileEngineFailureIsReported() = runBlocking {
        val root = Files.createTempDirectory("native-dispatch-downstream")
        try {
            val selected = RecordingRuntime(root.toString(), "started")
            val runtime = coordinator(selected, RecordingRuntime(root.toString(), "unused"))
            val profile = LlmProfile("profile", "Fixture", baseUrl = "http://fixture.invalid/v1", apiKey = "fixture", modelId = "fixture")
            val project = CodingProject("project", "Fixture", root.toString(), 1)
            val session = CodingSession("session", project.id, "Fixture", 1, engine = CodingEngine.PI)
            // As ownedRun's NativeSessionBound dispatch into a run that was already stopped: the collector's own failure.
            val rejected = CodingCommandRejected("Запуск уже остановлен")
            val failure = assertFailsWith<CodingCommandRejected> {
                runtime.run(project, session, "run", profile).collect { throw rejected }
            }
            // Stack trace recovery may wrap the original in a same-type copy; it must still be this failure, not a report of it.
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === rejected })

            selected.failure = IllegalStateException("Процесс движка завершился")
            assertEquals(listOf(CodingEvent.Notice("started"), CodingEvent.Failed("Процесс движка завершился"), CodingEvent.Finished),
                runtime.run(project, session, "run", profile).toList())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun persistedIdentitySelectsRegisteredStrategyForRunAndPlanning() = runBlocking {
        val root = Files.createTempDirectory("native-dispatch")
        try {
            val first = RecordingRuntime(root.toString(), "first")
            val second = RecordingRuntime(root.toString(), "second")
            val runtime = coordinator(first, second)
            val profile = LlmProfile("profile", "Fixture", baseUrl = "http://fixture.invalid/v1", apiKey = "fixture", modelId = "fixture")
            val project = CodingProject("project", "Fixture", root.toString(), 1)
            val session = CodingSession("session", project.id, "Fixture", 1, engine = CodingEngine.CODEX)

            val events = runtime.run(project, session, "run", profile).toList()
            val planning = runtime.runPlanning(project, session.copy(planningMode = true), "plan", profile).toList()

            assertTrue(first.calls.isEmpty())
            assertEquals(listOf("preflight", "run", "preflight", "planning"), second.calls)
            assertContains(events, CodingEvent.Notice("second"))
            assertContains(planning, CodingEvent.Notice("second"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun historyAndRemovalFollowCapabilitiesInsteadOfKnownEngineNames() = runBlocking {
        val first = RecordingRuntime("fixture", "first")
        val second = RecordingRuntime("fixture", "second")
        val runtime = coordinator(first, second)
        val pi = CodingSession("session", "project", "Fixture", 1, engine = CodingEngine.PI, piSessionId = "thread")
        val codex = pi.copy(engine = CodingEngine.CODEX)

        runtime.nativeToolResults(pi, setOf("call"))
        assertTrue(runtime.nativeToolResults(codex, setOf("call")).isEmpty())
        assertFalse(runtime.status(CodingEngine.PI).dependenciesRemovable == true)
        assertTrue(runtime.status(CodingEngine.CODEX).dependenciesRemovable == true)
        assertFailsWith<IllegalArgumentException> { runtime.uninstall(CodingEngine.PI) }
        runtime.uninstall(CodingEngine.CODEX)

        assertEquals(listOf("history"), first.calls)
        assertEquals(listOf("uninstall"), second.calls)
    }

    private fun coordinator(first: CodingRuntime, second: CodingRuntime) = DesktopCodingRuntime(
        listOf(
            NativeRuntimeBinding(backendCatalog.descriptor(io.aequicor.magicpaper.domain.CodingEngine.PI).copy(capabilities = setOf(BackendAgentCapability.NATIVE_TOOL_HISTORY)), first),
            NativeRuntimeBinding(backendCatalog.descriptor(io.aequicor.magicpaper.domain.CodingEngine.CODEX).copy(capabilities = setOf(BackendAgentCapability.MANAGED_INSTALLATION)), second),
        ), null, { CodingSkillSelection(emptyList()) }, testCommandChecks,
    )

    private class RecordingRuntime(override val rootPath: String, val label: String) : CodingRuntime {
        val calls = mutableListOf<String>()
        var failure: Throwable? = null
        override val supported = true
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY, "fixture")
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY, "fixture"))
        override suspend fun preflight(profile: LlmProfile) { calls += "preflight" }
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?,
            attachments: List<Attachment>): Flow<CodingEvent> {
            calls += "run"
            return events()
        }
        override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile): Flow<CodingEvent> {
            calls += "planning"
            return events()
        }
        private fun events(): Flow<CodingEvent> = flow {
            emit(CodingEvent.Notice(label))
            failure?.let { throw it }
            emit(CodingEvent.Finished)
        }
        override suspend fun nativeToolResults(session: CodingSession, callIds: Set<String>): List<CodingEvent.ToolFinished> {
            calls += "history"
            return emptyList()
        }
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() { calls += "uninstall" }
    }
}
