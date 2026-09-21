package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.recordPlanState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningOwnerTest {
    private var index = 0
    private fun stamp() = PlanningMachine.Stamp("command-${index++}", index.toLong())
    private val initial = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage", description = "Do it")))
    private fun repo() = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
    private suspend fun create(owner: PlanningStore) = owner.command(initial.id, PlanningMachine.Intent.Create(initial, stamp()))
    private suspend fun start(owner: PlanningStore) = owner.dispatch(initial.id,
        PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp()))

    @Test fun writesInputsInSamePlanStreamAndReplayNeverGrantsAdmission() = runTest {
        val events = InMemoryEventJournal(); val repo = repo(); val first = DefaultPlanningStore(repo, events)
        create(first); start(first)
        val saved = assertNotNull(first.currentAdmission(initial.id))
        assertEquals(listOf(initial.id), events.streams())
        assertTrue(events.read(initial.id).all { PlanInputCommit.from(it) != null })
        val reopened = DefaultPlanningStore(repo, events)
        assertEquals(first.planFor(initial.id), reopened.planFor(initial.id))
        assertNull(reopened.currentAdmission(initial.id))
        assertEquals(PlanningMachine.RunPhase.INTERRUPTED, reopened.machineStates.value[initial.id]?.run?.phase)
        start(reopened)
        assertEquals(saved.generation + 1, reopened.currentAdmission(initial.id)?.generation)
    }
    @Test fun legacyCommitAndUnresolvedIntentArePreservedWithOriginalSequence() = runTest {
        val events = InMemoryEventJournal(); val repo = repo()
        val legacy = initial.copy(runId = "legacy-run", intent = ExecutionIntent.RUN, revision = 7)
        events.append(initial.id, PLAN_STATE_OPERATION, 1, PlanJournalCommit(recordPlanState(null, legacy)).encode())
        val intent = events.append(initial.id, PlanJournalOperation.AGENT_INTENT.wire, 2)
        val owner = DefaultPlanningStore(repo, events)
        assertEquals(DecisionCompiler.migrate(legacy), owner.planFor(initial.id)); assertNull(owner.currentAdmission(initial.id))
        assertEquals(listOf(intent), owner.unsettled(initial.id)); assertNotNull(start(owner).rejection)
        val records = events.read(initial.id)
        val reopened = DefaultPlanningStore(repo, events)
        reopened.plans(); assertEquals(records, events.read(initial.id))
    }
    @Test fun legacySnapshotIsImportedOnlyOnceWithoutInventingRun() = runTest {
        val repo = repo(); repo.save(initial.copy(intent = ExecutionIntent.RUN, runId = "saved"))
        val events = InMemoryEventJournal(); val first = DefaultPlanningStore(repo, events)
        first.plans(); val records = events.read(initial.id)
        assertEquals(1, records.size); assertNull(first.currentAdmission(initial.id))
        DefaultPlanningStore(repo, events).plans(); assertEquals(records, events.read(initial.id))
    }
    @Test fun exactLostAckIsRecoveredAndDoesNotDuplicateCommand() = runTest {
        val memory = InMemoryEventJournal()
        var lose = false
        val events = object : EventJournal by memory {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val result = memory.append(expected, operation, at, detail)
                if(lose) error("lost acknowledgement")
                return result
            }
        }
        val owner = DefaultPlanningStore(repo(), events); create(owner); lose = true
        val input = PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp())
        assertNull(owner.dispatch(initial.id, input).rejection)
        assertNotNull(owner.currentAdmission(initial.id)); assertEquals(2, memory.read(initial.id).size)
        assertTrue(owner.dispatch(initial.id, input).effects.isEmpty()); assertEquals(2, memory.read(initial.id).size)
    }
    @Test fun changedHistoryWithMatchingFinalInputCannotProveLostAck() = runTest {
        val memory = InMemoryEventJournal(); var corrupt = false
        val events = object : EventJournal by memory {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val result = memory.append(expected, operation, at, detail)
                if(corrupt) error("lost acknowledgement")
                return result
            }
            override suspend fun snapshot(stream: String): JournalSnapshot = memory.snapshot(stream).let {
                if(!corrupt) it else it.copy(records = it.records.mapIndexed { index, record -> if(index == 0) record.copy(at = record.at + 1) else record })
            }
        }
        val owner = DefaultPlanningStore(repo(), events); create(owner); corrupt = true
        assertFailsWith<PlanningPersistenceException> { start(owner) }
        assertNull(owner.currentAdmission(initial.id)); assertTrue(owner.machineStates.value.getValue(initial.id).persistenceUnknown)
    }
    @Test fun wrongAcknowledgementNeverGrantsAdmission() = runTest {
        val memory = InMemoryEventJournal(); var forged = false
        val events = object : EventJournal by memory {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String) =
                memory.append(expected, operation, at, detail)?.let { if(forged) it.copy(stream = "other") else it }
        }
        val owner = DefaultPlanningStore(repo(), events); create(owner); forged = true
        assertFailsWith<PlanningPersistenceException> { start(owner) }; assertNull(owner.currentAdmission(initial.id))
    }
    @Test fun unknownExternalOutcomeSurvivesRestartAndRequiresSpecificReconciliation() = runTest {
        val memory = InMemoryEventJournal(); val repo = repo(); val owner = DefaultPlanningStore(repo, memory)
        create(owner); start(owner)
        val intent = owner.beginIntent(initial.id, PlanJournalOperation.AGENT_INTENT, "stage", "attempt", checkNotNull(owner.currentAdmission(initial.id)))
        owner.markIntentUnknown(intent); assertNull(owner.currentAdmission(initial.id))
        val reopened = DefaultPlanningStore(repo, memory); reopened.plans()
        assertNotNull(start(reopened).rejection)
        assertEquals(listOf(intent), reopened.unsettled(initial.id))
        reopened.reconcileIntents(checkNotNull(reopened.planFor(initial.id)), listOf(intent), setOf(intent.seq), PlanRecoveryAuthority.USER)
        assertNull(reopened.currentAdmission(initial.id)); assertNull(start(reopened).rejection)
    }
    @Test fun checkpointFailurePublishesAcceptedPlanButFencesAdmissionUntilRecovery() = runTest {
        val repo = repo(); val memory = InMemoryEventJournal(); var fail = false
        val checkpoint = object : PlanningCheckpointStore by repo {
            override suspend fun save(plan: Plan) { if(fail) error("checkpoint"); repo.save(plan) }
        }
        val owner = DefaultPlanningStore(checkpoint, memory); create(owner); fail = true
        assertFailsWith<PlanningPersistenceException> { start(owner) }
        assertEquals(ExecutionIntent.RUN, owner.planFor(initial.id)?.intent); assertNull(owner.currentAdmission(initial.id))
        fail = false; owner.recover(); assertNull(owner.currentAdmission(initial.id))
        assertEquals(PlanningMachine.RunPhase.INTERRUPTED, owner.machineStates.value[initial.id]?.run?.phase)
        assertNull(start(owner).rejection)
    }
    @Test fun cancellationAfterWritePreservesCancellationAndRequiresRecovery() = runTest {
        val memory = InMemoryEventJournal(); var cancel = false
        val original = CancellationException("cancel")
        val events = object : EventJournal by memory {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val result = memory.append(expected, operation, at, detail)
                if(cancel) throw original
                return result
            }
        }
        val owner = DefaultPlanningStore(repo(), events); create(owner); cancel = true
        val thrown = assertFailsWith<CancellationException> { start(owner) }
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === original })
        assertNull(owner.currentAdmission(initial.id)); assertNotNull(owner.failure.value)
    }
    @Test fun corruptStreamOrderEpochDuplicateInputsAndRevisionAreRejected() = runTest {
        val memory = InMemoryEventJournal(); val repo = repo(); val owner = DefaultPlanningStore(repo, memory)
        create(owner); start(owner)
        val valid = memory.snapshot(initial.id)
        val last = valid.records.last()
        val cases = listOf(
            valid.copy(revision = valid.revision.copy(seq = -1)),
            valid.copy(revision = valid.revision.copy(resetEpoch = -1)),
            valid.copy(revision = valid.revision.copy(resetEpoch = 7)),
            valid.copy(records = valid.records.map { it.copy(stream = "other") }),
            valid.copy(records = valid.records.reversed()),
            valid.copy(revision = valid.revision.copy(seq = last.seq + 1), records = valid.records + last.copy(seq = last.seq + 1)),
            valid.copy(revision = valid.revision.copy(seq = last.seq + 4)),
        )
        for(snapshot in cases) {
            val corrupt = object : EventJournal by memory { override suspend fun snapshot(stream: String) = snapshot }
            assertFailsWith<PlanningPersistenceException> { DefaultPlanningStore(repo, corrupt).plans() }
        }
    }
    @Test fun deletionIsDurableTombstoneAndCannotBeResurrectedByCheckpoint() = runTest {
        val memory = InMemoryEventJournal(); val repo = repo(); val owner = DefaultPlanningStore(repo, memory)
        create(owner); owner.deletePlan(initial.id); repo.save(initial)
        val reopened = DefaultPlanningStore(repo, memory); assertTrue(reopened.plans().isEmpty())
        assertNotNull(reopened.dispatch(initial.id, PlanningMachine.Intent.Create(initial, stamp())).rejection)
        assertNull(repo.planFor(initial.id))
    }
    @Test fun credentialsAreRedactedBeforeJournalAndCheckpointPublication() = runTest {
        val secret = "fixture-secret-value"
        val events = InMemoryEventJournal(); val owner = DefaultPlanningStore(repo(), events) { setOf(secret) }
        owner.command(initial.id, PlanningMachine.Intent.Create(initial.copy(goal = "User text $secret"), stamp()))
        assertFalse(checkNotNull(owner.planFor(initial.id)).goal.contains(secret))
        assertFalse(events.read(initial.id).any { secret in it.detail })
    }
    @Test fun callerCannotUseCompatibilityInputToBypassCommands() = runTest {
        val owner = DefaultPlanningStore(repo(), InMemoryEventJournal()); create(owner)
        assertFailsWith<IllegalArgumentException> { owner.dispatch(initial.id, PlanningMachine.Fact.LegacyCheckpoint(initial.copy(revision = 1), stamp())) }
    }
    @Test fun pausedOpenIntentCannotBeDeletedOrWipedAndKnownOutcomeStillCommits() = runTest {
        val events = InMemoryEventJournal(); val owner = DefaultPlanningStore(repo(), events)
        create(owner); start(owner)
        val ref = checkNotNull(owner.currentAdmission(initial.id))
        val intent = owner.beginIntent(initial.id, PlanJournalOperation.AGENT_INTENT, "stage", "attempt", ref)
        owner.command(initial.id, PlanningMachine.Intent.Pause(stamp()))
        val before = events.snapshot(initial.id)
        assertFailsWith<IllegalArgumentException> { owner.deletePlan(initial.id) }
        assertFailsWith<IllegalStateException> { owner.wipe() }
        assertEquals(before, events.snapshot(initial.id))
        owner.finishIntent(intent, PlanIntentStatus.COMPLETED)
        assertTrue(owner.unsettled(initial.id).isEmpty())
        owner.deletePlan(initial.id)
        assertTrue(owner.plans().isEmpty())
    }
    @Test fun restoredPausedRunCannotResumeUsingTheOldProcessGrant() = runTest {
        val events = InMemoryEventJournal(); val repo = repo(); val owner = DefaultPlanningStore(repo, events)
        create(owner); start(owner)
        val ref = checkNotNull(owner.currentAdmission(initial.id))
        owner.command(initial.id, PlanningMachine.Intent.Pause(stamp()))
        val reopened = DefaultPlanningStore(repo, events); reopened.plans()
        assertNotNull(reopened.dispatch(initial.id, PlanningMachine.Intent.Resume(ref, stamp())).rejection)
        assertNull(reopened.currentAdmission(initial.id)); assertNull(start(reopened).rejection)
    }

    @Test fun refreshRejectsValidButTruncatedOrRewrittenHistoryWithoutReplacingProjection() = runTest {
        for(rewrite in listOf(false, true)) {
            val memory = InMemoryEventJournal(); var replacement: JournalSnapshot? = null
            val journal = object : EventJournal by memory {
                override suspend fun snapshot(stream: String) = replacement ?: memory.snapshot(stream)
            }
            val owner = DefaultPlanningStore(repo(), journal); create(owner); start(owner)
            owner.command(initial.id, PlanningMachine.Intent.Pause(stamp()))
            val previous = memory.snapshot(initial.id); val plan = owner.planFor(initial.id)
            val records = if(rewrite) previous.records.mapIndexed { n, record ->
                if(n != 0) record else {
                    val envelope = checkNotNull(PlanInputCommit.from(record))
                    record.copy(detail = envelope.copy(input = (envelope.input as PlanningMachine.Intent.Create)
                        .copy(plan = initial.copy(goal = "Rewritten history"))).encode())
                }
            } else previous.records.dropLast(1)
            replacement = previous.copy(records = records, revision = previous.revision.copy(seq = records.last().seq))
            assertFailsWith<PlanningPersistenceException> { owner.unsettled(initial.id) }
            assertEquals(plan, owner.plans.value.single()); assertNotNull(owner.failure.value)
            assertNull(owner.currentAdmission(initial.id))
        }
    }

    @Test fun scheduledRuleAndDeliveryIdentitiesSurviveCancelAndReplay() = runTest {
        val repo = repo(); val events = InMemoryEventJournal()
        val legacy = initial.copy(runId = "run", confirmedRevision = 0, parentSessionId = "parent")
            .applyScheduleCommands(listOf(ScheduleCommand(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 1000), text = "Previous")),
                "previous", "parent", emptySet(), 1, generate = sequenceOf("schedule-input:schedule:0", "schedule-input:schedule:1").iterator()::next)
        repo.save(legacy)
        val owner = DefaultPlanningStore(repo, events)
        val created = owner.command(initial.id, PlanningMachine.Intent.Schedule("run", listOf(
            ScheduleCommand(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 1000), text = "New")),
            "next", "parent", emptySet(), null, PlanningMachine.Stamp("schedule-input", 2)))
        val rule = created.scheduledMessages.last()
        assertEquals("schedule-input:schedule:2", rule.id)
        assertEquals("schedule-input:schedule:3", rule.deliveryId)
        val cancelled = owner.command(initial.id, PlanningMachine.Intent.Schedule("run", listOf(
            ScheduleCommand(operation = ScheduleOperation.CANCEL, ruleId = rule.id)),
            "cancel", "parent", emptySet(), null, stamp()))
        assertEquals(ScheduledMessageStatus.CANCELLED, cancelled.scheduledMessages.last().status)
        owner.recover()
        assertEquals(cancelled, owner.planFor(initial.id))
        assertEquals(cancelled, DefaultPlanningStore(repo, events).planFor(initial.id))
        assertNull(owner.currentAdmission(initial.id))
    }
    @Test fun liveStopFactsRequireExplicitIdentityWhileOlderAcceptedRecordsRemainReadable() = runTest {
        val events = InMemoryEventJournal(); val repo = repo(); val owner = DefaultPlanningStore(repo, events)
        create(owner); start(owner)
        owner.command(initial.id, PlanningMachine.Intent.Stop(stamp()))
        val stop = checkNotNull(owner.machineStates.value[initial.id]?.stopId)
        assertFailsWith<IllegalArgumentException> { owner.dispatch(initial.id, PlanningMachine.Fact.StopConfirmed(stamp())) }
        assertFailsWith<IllegalArgumentException> { owner.dispatch(initial.id, PlanningMachine.Fact.StopUnknown(stamp())) }
        val before = events.snapshot(initial.id)
        val oldInput = PlanningMachine.Fact.StopConfirmed(stamp())
        events.append(before.revision, PlanJournalOperation.STOP_CONFIRMED.wire, oldInput.stamp.at,
            PlanInputCommit(oldInput, before.revision.resetEpoch).encode())
        val reopened = DefaultPlanningStore(repo, events)
        assertFalse(checkNotNull(reopened.planFor(initial.id)).stopping)
        assertEquals(stop, reopened.machineStates.value[initial.id]?.stopId)
        assertNull(reopened.currentAdmission(initial.id))
    }

}
