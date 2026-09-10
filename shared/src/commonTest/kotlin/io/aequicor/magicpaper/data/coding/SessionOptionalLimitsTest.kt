package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class SessionOptionalLimitsTest {
    private class Fixture(val limits: OrganismLimits = OrganismLimits()) {
        var now = 1_000L
        val storage = InMemoryKeyValueStore()
        val store = SessionOrganismStore(storage) { now }
        lateinit var initial: SessionOrganism
        suspend fun initialize() {
            initial = store.adopt("project", CodingSession("root", "project", "Task", now,
                researchMode = true, planningRulesSnapshot = PlanningRulesSettings().snapshot()), emptyList(), limits)
            store.beginRun(initial.id, "root")
        }
        suspend fun authority(id: String = "root"): SessionAuthority {
            val saved = store.get(initial.id)
            val node = saved.sessions.getValue(id)
            return SessionAuthority(saved.projectId, saved.id, id, node.generation, node.mode)
        }
        suspend fun child(id: String, parent: String = "root", tokens: Long = 0): SessionOrganism =
            store.command(authority(parent), id, OrganismCommand(OrganismAction.CREATE, name = id, tokens = tokens,
                task = SessionTask("Inspect", parent, "Findings")))
    }

    @Test fun defaultPolicyAllowsLongRunningLargeUsageAndMoreThanEightDeepWorkers() = runTest {
        val f = Fixture(); f.initialize()
        f.now += 3 * 3_600_000
        val charged = f.store.charge(f.authority(), 2_100_000)
        assertEquals(2_100_000L, charged.sessions.getValue("root").spentTokens)
        assertEquals(SessionDesiredState.RUN, charged.sessions.getValue("root").desired)
        var parent = "root"
        repeat(12) { index ->
            f.child("child-$index", parent)
            parent = "session-child-$index"
            f.store.beginRun(f.initial.id, parent)
            f.store.check(f.authority(parent))
        }
        val saved = f.store.get(f.initial.id)
        assertEquals(13, saved.sessions.values.count { it.observed == SessionObservedState.RUNNING })
        assertTrue(saved.sessions.values.all { it.remainingTokens == 0L })
        assertEquals(OrganismLimits(), saved.limits)
        assertEquals(1, saved.limitPolicyVersion)
    }

    @Test fun defaultPolicyAllowsMoreThanThreeRestoresAndUntruncatedLargeContext() = runTest {
        val f = Fixture(); f.initialize(); f.child("child")
        repeat(5) { index ->
            val node = f.store.get(f.initial.id).sessions.getValue("session-child")
            f.store.observe(f.initial.id, node.id, node.generation, SessionObservedState.STOPPED)
            f.store.command(f.authority(), "restore-$index", OrganismCommand(OrganismAction.RESTORE,
                target = node.id, reason = "Task and outcomes checked"))
        }
        val text = "x".repeat(70_000)
        repeat(140) { index ->
            f.store.command(f.authority(), "context-$index", OrganismCommand(OrganismAction.SEND,
                target = "session-child", packet = SessionContextPacket(if (index == 0) text else "Context $index")))
        }
        val saved = f.store.get(f.initial.id)
        assertEquals(5, saved.sessions.getValue("session-child").retryCount)
        assertEquals(140, saved.outbox.size)
        assertEquals(text, saved.outbox.first().packet.text)
    }

    @Test fun explicitTaskLimitAllowsAnExhaustedGrantAndStopsAtTheWholeTaskBudget() = runTest {
        val f = Fixture(OrganismLimits(tokens = 100)); f.initialize(); f.child("child", tokens = 10)
        f.store.beginRun(f.initial.id, "session-child")
        val child = f.authority("session-child")
        val charged = f.store.charge(child, 11)
        assertEquals(0L, charged.sessions.getValue(child.sessionId).remainingTokens)
        assertEquals(SessionDesiredState.RUN, charged.sessions.getValue(child.sessionId).desired)
        f.store.check(child)
        assertEquals(100L, charged.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
        val exhausted = f.store.charge(child, 89)
        assertEquals(SessionDesiredState.STOP, exhausted.sessions.getValue(child.sessionId).desired)
        assertEquals(SessionObservedState.STOPPING, exhausted.sessions.getValue(child.sessionId).observed)
        assertTrue(exhausted.sessions.values.all { it.remainingTokens == 0L })
        assertFailsWith<IllegalArgumentException> { f.store.check(f.authority()) }
    }

    @Test fun explicitOneAgentLimitCountsNativeWorkAndAllowsPendingChildren() = runTest {
        val f = Fixture(OrganismLimits(activeSessions = 1)); f.initialize(); f.child("child")
        val saved = f.store.get(f.initial.id)
        assertEquals(SessionObservedState.RUNNING, saved.sessions.getValue("root").observed)
        assertEquals(SessionObservedState.PENDING, saved.sessions.getValue("session-child").observed)
        assertFailsWith<IllegalArgumentException> { f.store.beginRun(saved.id, "session-child") }
        // The enduring parent remains open between its own native turns.
        f.store.observe(saved.id, "root", saved.sessions.getValue("root").generation, SessionObservedState.PENDING)
        assertEquals(SessionObservedState.RUNNING, f.store.beginRun(saved.id, "session-child").observed)
    }

    @Test fun explicitDurationDepthContextQueueAndRetryLimitsRemainEnforced() = runTest {
        val timed = Fixture(OrganismLimits(durationMillis = 100)); timed.initialize(); timed.now += 101
        assertFailsWith<IllegalArgumentException> { timed.store.check(timed.authority()) }
        val deep = Fixture(OrganismLimits(depth = 2)); deep.initialize(); deep.child("child")
        assertFailsWith<IllegalArgumentException> { deep.child("grandchild", "session-child") }
        val context = Fixture(OrganismLimits(contextCharacters = 5)); context.initialize()
        assertFailsWith<IllegalArgumentException> { context.child("child") }
        val queued = Fixture(OrganismLimits(queueSize = 1)); queued.initialize(); queued.child("child")
        assertFailsWith<IllegalArgumentException> { queued.child("second") }
        val retry = Fixture(OrganismLimits(retries = 0)); retry.initialize(); retry.child("child")
        val node = retry.store.get(retry.initial.id).sessions.getValue("session-child")
        retry.store.observe(retry.initial.id, node.id, node.generation, SessionObservedState.STOPPED)
        assertFailsWith<IllegalArgumentException> { retry.store.command(retry.authority(), "restore", OrganismCommand(
            OrganismAction.RESTORE, target = node.id, reason = "Check complete")) }
    }

    @Test fun liveLimitChangesKeepActualSpendAndNeverRestoreStoppedWork() = runTest {
        val f = Fixture(); f.initialize(); f.child("child")
        f.store.charge(f.authority(), 80)
        f.store.requestUserStop(f.initial.id, "session-child", "stop", archive = false)
        val before = f.store.get(f.initial.id).sessions.getValue("session-child")
        val limited = f.store.applyLimits(f.initial.id, OrganismLimits(tokens = 100))
        assertEquals(20L, limited.sessions.values.sumOf { it.remainingTokens })
        val larger = f.store.applyLimits(f.initial.id, OrganismLimits(tokens = 200))
        assertEquals(120L, larger.sessions.values.sumOf { it.remainingTokens })
        val expired = f.store.applyLimits(f.initial.id, OrganismLimits(tokens = 50))
        assertEquals(80L, expired.sessions.values.sumOf { it.spentTokens })
        assertTrue(expired.sessions.values.all { it.remainingTokens == 0L })
        assertFailsWith<IllegalArgumentException> { f.store.check(f.authority()) }
        val unbounded = f.store.applyLimits(f.initial.id, OrganismLimits())
        assertEquals(80L, unbounded.sessions.values.sumOf { it.spentTokens })
        assertTrue(unbounded.sessions.values.all { it.remainingTokens == 0L })
        assertEquals(before, unbounded.sessions.getValue("session-child"))
        f.store.check(f.authority())
        assertEquals(unbounded, f.store.applyLimits(f.initial.id, OrganismLimits()))
        assertEquals(unbounded, SessionOrganismStore(f.storage) { f.now }.get(unbounded.id))
    }

    @Test fun legacyHiddenLimitsMigrateOnceAndStoppedPlanStillNeedsFreshExplicitRetry() = runTest {
        val storage = InMemoryKeyValueStore()
        val store = SessionOrganismStore(storage) { 1_000L }
        val root = CodingSession("root", "project", "Task", 1, planningMode = true)
        val child = CodingSession("worker", "project", "Stage", 1, parentSessionId = root.id)
        val saved = store.adopt("project", root, listOf(child), boundedOrganismTestLimits)
        val binding = SessionLegacyAttempt("plan", "run", "stage", "attempt", turnIndex = 0, generation = 1)
        val task = SessionTask("Audit", root.id, "Verified findings")
        val legacy = saved.copy(sessions = saved.sessions +
            (root.id to saved.sessions.getValue(root.id).copy(remainingTokens = 926_000)) +
            (child.id to saved.sessions.getValue(child.id).copy(remainingTokens = 0, spentTokens = 93_956,
                generation = 1, lastStartedGeneration = 1, legacyAttempt = binding, task = task,
                desired = SessionDesiredState.STOP, observed = SessionObservedState.STOPPED)))
        val raw = Json.parseToJsonElement(Json.encodeToString(legacy)).jsonObject
        storage.write("session-organism-${saved.id}", JsonObject(raw - "limitPolicyVersion").toString())
        val reloaded = SessionOrganismStore(storage) { 20_000_000L }
        val migrated = reloaded.get(saved.id)
        assertEquals(OrganismLimits(), migrated.limits)
        assertEquals(1, migrated.limitPolicyVersion)
        assertEquals(93_956L, migrated.sessions.getValue(child.id).spentTokens)
        assertTrue(migrated.sessions.values.all { it.remainingTokens == 0L })
        assertEquals(SessionDesiredState.STOP, migrated.sessions.getValue(child.id).desired)
        assertEquals(legacy.audit, migrated.audit.dropLast(1))
        assertEquals(migrated, reloaded.get(saved.id))
        assertFailsWith<IllegalArgumentException> { reloaded.admitPlanWorker(saved.id, child, task, binding, null, setOf("stage")) }
        val authorization = assertNotNull(reloaded.authorizePlanRetry(saved.id, child.id, binding))
        val admitted = reloaded.admitPlanWorker(saved.id, child, task, binding, null, setOf("stage"), authorization)
        assertEquals(2L, admitted.sessions.getValue(child.id).generation)
        assertEquals(SessionDesiredState.RUN, admitted.sessions.getValue(child.id).desired)
        assertEquals(93_956L, admitted.sessions.getValue(child.id).spentTokens)
        assertEquals(0L, admitted.sessions.getValue(child.id).remainingTokens)
        assertEquals(SessionObservedState.RUNNING, reloaded.beginRun(saved.id, child.id).observed)
    }

    @Test fun elapsedTimeAloneCannotQuarantineAnUnconfirmedOperation() = runTest {
        val f = Fixture(); f.initialize(); f.child("child")
        f.store.beginRun(f.initial.id, "session-child")
        val before = f.store.get(f.initial.id)
        val pending = before.copy(operations = before.operations + ("long-operation" to OrganismOperation(
            "long-operation", "APPLICATION", "session-child", SessionOperationState.ACCEPTED)),
            audit = before.audit + SessionAuditEvent("long-operation", "APPLICATION", "INTEGRATE", setOf("session-child"), "Started", f.now))
        f.storage.write("session-organism-${before.id}", Json.encodeToString(pending))
        f.now += 600_000
        f.store.command(f.authority(), "signal", OrganismCommand(OrganismAction.SIGNAL,
            target = "session-child", reason = "Still working"))
        val inspected = f.store.inspectSignals(before.id)
        assertTrue(inspected.diagnoses.single().evidence.isEmpty())
        assertEquals(SessionDesiredState.RUN, inspected.sessions.getValue("session-child").desired)
    }
}
