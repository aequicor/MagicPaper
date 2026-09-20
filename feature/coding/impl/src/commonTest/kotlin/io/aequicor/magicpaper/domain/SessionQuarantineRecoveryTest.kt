package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A quarantined root is a dead end unless an explicit recovery closes its unknown outcome. */
class SessionQuarantineRecoveryTest {
    private suspend fun fixture(mode: CodingInteractionMode = CodingInteractionMode.CODE): SessionOrganismTestFixture {
        val f = SessionOrganismTestFixture()
        f.initialize(mode)
        return f
    }

    private suspend fun quarantine(f: SessionOrganismTestFixture, sessionId: String, operation: String = "native-exec"): Long {
        val id = f.root.organismId!!
        val generation = f.store.get(id).sessions.getValue(sessionId).generation
        f.store.quarantine(id, sessionId, generation, operation, "Неизвестный исход powershell")
        f.service.project(f.store.get(id))
        return generation
    }

    private suspend fun quarantines(f: SessionOrganismTestFixture, sessionId: String) =
        f.store.get(f.root.organismId!!).unresolvedQuarantines(sessionId).map { it.operationId }.toSet()

    private fun proof(ids: Set<String>) = SessionQuarantineProof(ids, listOf("Процесс сверён; журнал инструмента подтвердил исход"))

    private suspend fun stored(f: SessionOrganismTestFixture, sessionId: String) =
        f.projects.sessions(f.project.id).single { it.id == sessionId }

    @Test fun unresolvedQuarantineBlocksANewUserTurn() = runTest {
        val f = fixture()
        quarantine(f, "root")
        assertEquals(setOf("quarantine-native-exec"), quarantines(f, "root"))
        val blocked = assertFailsWith<SessionQuarantineBlocked> { f.service.prepareUserTurn(f.root, "human-request") }
        assertEquals("root", blocked.sessionId, "Блокировка типизирована: интерфейс открывает восстановление сам")
        assertEquals(SessionObservedState.UNKNOWN, f.store.get(f.root.organismId!!).sessions.getValue("root").observed)
    }

    @Test fun hostProofReopensTheRootForANewUserTurn() = runTest {
        val f = fixture()
        val generation = quarantine(f, "root")
        var requests = 0
        f.service.reconcileUnknownOutcomes = { request ->
            requests++
            assertEquals(generation, request.generation)
            assertEquals(request.generation, request.session.runtimeGeneration)
            assertEquals(listOf("quarantine-native-exec"), request.quarantines.map { it.operationId })
            proof(request.quarantines.map { it.operationId }.toSet())
        }
        assertEquals(QuarantineRecoveryOutcome.RESOLVED, f.service.reconcileQuarantine(f.root, userConfirmed = false))
        val resolved = f.store.get(f.root.organismId!!)
        assertEquals(1, requests)
        assertTrue(resolved.unresolvedQuarantines("root").isEmpty())
        val node = resolved.sessions.getValue("root")
        assertEquals(SessionDesiredState.STOP, node.desired)
        assertEquals(SessionObservedState.STOPPED, node.observed)
        val resolution = resolved.audit.single { it.action == QUARANTINE_RESOLVED_ACTION }
        assertEquals("APPLICATION", resolution.actor)
        assertEquals(quarantineResolutionId("root", "quarantine-native-exec"), resolution.operationId)
        f.service.prepareUserTurn(f.root, "human-request")
        val reopened = f.store.get(f.root.organismId!!).sessions.getValue("root")
        assertEquals(SessionDesiredState.RUN, reopened.desired)
        assertEquals(SessionObservedState.PENDING, reopened.observed)
        assertEquals(node.generation + 1, reopened.generation)
        assertEquals(node.version + 1, reopened.version)
    }

    @Test fun unprovenOutcomeKeepsTheQuarantineUntilTheUserConfirms() = runTest {
        val f = fixture()
        quarantine(f, "root")
        val before = f.store.get(f.root.organismId!!)
        var requests = 0
        f.service.reconcileUnknownOutcomes = { requests++; null }
        assertEquals(QuarantineRecoveryOutcome.NEEDS_CONFIRMATION, f.service.reconcileQuarantine(f.root, userConfirmed = false))
        assertEquals(1, requests)
        assertEquals(before, f.store.get(f.root.organismId!!))
        assertFailsWith<SessionQuarantineBlocked> { f.service.prepareUserTurn(f.root, "human-request") }

        assertEquals(QuarantineRecoveryOutcome.RESOLVED, f.service.reconcileQuarantine(f.root, userConfirmed = true))
        val resolved = f.store.get(f.root.organismId!!)
        assertTrue(resolved.unresolvedQuarantines("root").isEmpty())
        val resolution = resolved.audit.single { it.action == QUARANTINE_RESOLVED_ACTION }
        assertEquals("USER", resolution.actor, "Только человек может закрыть недоказанный исход")
        assertTrue(resolution.reason.contains("powershell"), "Подтверждение называет проверенную операцию")
        f.service.prepareUserTurn(f.root, "human-request")
        assertEquals(SessionObservedState.PENDING, f.store.get(f.root.organismId!!).sessions.getValue("root").observed)
    }

    @Test fun proofMustCoverEveryCurrentQuarantine() = runTest {
        val f = fixture()
        quarantine(f, "root", "first-exec")
        quarantine(f, "root", "second-exec")
        val before = f.store.get(f.root.organismId!!)
        f.service.reconcileUnknownOutcomes = { proof(setOf("quarantine-first-exec")) }
        assertFailsWith<IllegalArgumentException> { f.service.reconcileQuarantine(f.root, userConfirmed = false) }
        assertEquals(before, f.store.get(f.root.organismId!!))
        f.service.reconcileUnknownOutcomes = { request -> proof(request.quarantines.map { it.operationId }.toSet()) }
        assertEquals(QuarantineRecoveryOutcome.RESOLVED, f.service.reconcileQuarantine(f.root, userConfirmed = false))
        assertEquals(2, f.store.get(f.root.organismId!!).audit.count { it.action == QUARANTINE_RESOLVED_ACTION })
    }

    @Test fun emptyProofEvidenceIsRejected() = runTest {
        val f = fixture()
        quarantine(f, "root")
        val before = f.store.get(f.root.organismId!!)
        f.service.reconcileUnknownOutcomes = { request -> SessionQuarantineProof(request.quarantines.map { it.operationId }.toSet(), listOf(" ")) }
        assertFailsWith<IllegalArgumentException> { f.service.reconcileQuarantine(f.root, userConfirmed = false) }
        assertEquals(before, f.store.get(f.root.organismId!!))
    }

    @Test fun aSessionWithoutQuarantineIsNotTouched() = runTest {
        val f = fixture()
        val before = f.store.get(f.root.organismId!!)
        var requests = 0
        f.service.reconcileUnknownOutcomes = { requests++; proof(setOf("quarantine-native-exec")) }
        assertEquals(QuarantineRecoveryOutcome.NO_QUARANTINE, f.service.reconcileQuarantine(f.root, userConfirmed = true))
        assertEquals(0, requests)
        assertEquals(before, f.store.get(f.root.organismId!!))
    }

    @Test fun childQuarantineIsResolvedSeparatelyAndReturnsItsBudgetToTheParent() = runTest {
        val (f, childId) = planningChild()
        val id = f.root.organismId!!
        quarantine(f, childId)
        val before = f.store.get(id)
        val childTokens = before.sessions.getValue(childId).remainingTokens
        val rootBefore = before.sessions.getValue("root").remainingTokens
        assertTrue(childTokens > 0, "Запущенный ребёнок держит выделенный бюджет")
        f.service.reconcileUnknownOutcomes = { request -> proof(request.quarantines.map { it.operationId }.toSet()) }
        assertEquals(QuarantineRecoveryOutcome.RESOLVED, f.service.reconcileQuarantine(stored(f, childId), userConfirmed = false))
        val resolved = f.store.get(id)
        assertTrue(resolved.unresolvedQuarantines(childId).isEmpty())
        assertTrue(resolved.unresolvedQuarantines("root").isEmpty())
        assertEquals(rootBefore + childTokens, resolved.sessions.getValue("root").remainingTokens)
        assertEquals(SessionObservedState.STOPPED, resolved.sessions.getValue(childId).observed)
    }

    @Test fun controlQuarantineOfAStoppingChildIsResolvedAfterItsStopIsConfirmed() = runTest {
        val (f, childId) = planningChild()
        val id = f.root.organismId!!
        val authority = f.store.get(id).let { organism ->
            val node = organism.sessions.getValue("root")
            SessionAuthority(f.project.id, id, "root", node.generation, node.mode)
        }
        f.store.command(authority, "quarantine-child",
            OrganismCommand(OrganismAction.QUARANTINE, target = childId, reason = "Процесс не подтвердил остановку"))
        assertEquals(SessionObservedState.STOPPING, f.store.get(id).sessions.getValue(childId).observed)
        assertEquals(setOf("quarantine-child"), quarantines(f, childId))
        f.service.reconcileUnknownOutcomes = { request -> proof(request.quarantines.map { it.operationId }.toSet()) }
        assertFailsWith<IllegalArgumentException> { f.service.reconcileQuarantine(stored(f, childId), userConfirmed = true) }
        assertEquals(SessionObservedState.STOPPING, f.store.get(id).sessions.getValue(childId).observed)
        f.service.project(f.store.observe(id, childId, f.store.get(id).sessions.getValue(childId).generation, SessionObservedState.STOPPED))
        assertEquals(QuarantineRecoveryOutcome.RESOLVED, f.service.reconcileQuarantine(stored(f, childId), userConfirmed = true))
        val resolved = f.store.get(id).sessions.getValue(childId)
        assertEquals(SessionDesiredState.STOP, resolved.desired)
        assertEquals(SessionObservedState.STOPPED, resolved.observed)
        assertTrue(f.store.get(id).unresolvedQuarantines(childId).isEmpty())
    }

    /** Планирующий корень с запущенным ребёнком: карантин ребёнка не затрагивает аудит корня. */
    private suspend fun planningChild(): Pair<SessionOrganismTestFixture, String> {
        val f = fixture(CodingInteractionMode.PLANNING)
        f.service.startChild = { child, _ ->
            f.store.beginRun(f.root.organismId!!, child.id)
            f.service.project(f.store.get(f.root.organismId!!))
        }
        f.create("create")
        val childId = f.store.get(f.root.organismId!!).sessions.values.single { it.kind == SessionKind.SESSION }.id
        return f to childId
    }
}
