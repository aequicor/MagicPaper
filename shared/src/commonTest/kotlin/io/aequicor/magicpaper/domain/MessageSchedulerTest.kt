package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.planning.JsonPlanningRepository
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageSchedulerTest {
    private fun plan() = Plan("plan", "project", "Goal", parentSessionId = "orchestrator", runId = "run",
        confirmedRevision = 1, intent = ExecutionIntent.RUN, phase = ExecutionPhase.EXECUTING,
        milestones = listOf(Milestone("a", "Same name"), Milestone("b", "Same name")),
        tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("a", "b")),
            DecisionNode("a", "Same name", DecisionKind.STAGE, stageId = "a"), DecisionNode("b", "Same name", DecisionKind.STAGE, stageId = "b")))
    private fun register(p: Plan = plan(), trigger: MessageTrigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.TASK_SUCCEEDED, taskId = "a"),
        origin: String = "request", target: String? = "b") = p.applyScheduleCommands(
        listOf(ScheduleCommand(trigger = trigger, targetTaskId = target, text = "Review the result")), origin, "orchestrator", setOf("question"), 100)
    private fun event(kind: MessageEventKind = MessageEventKind.TASK_SUCCEEDED, task: String? = "a", run: String = "run", at: Long = 200,
        id: String = "event", question: String? = null) = MessageEvent(id, id, "plan", run, kind, at, "Verified output", task, question)

    @Test fun exactTaskAndRunMatchDespiteDuplicateNamesAndRename() {
        val p = register()
        listOf(event(task = "b"), event(run = "other"), event(kind = MessageEventKind.RESULT_RETURNED)).forEach {
            assertEquals(ScheduledMessageStatus.WAITING, p.copy(messageEvents = listOf(it)).advanceScheduledMessages(300).scheduledMessages.single().status)
        }
        val renamed = p.copy(milestones = p.milestones.map { it.copy(title = "Renamed") }, messageEvents = listOf(event()))
        val ready = renamed.advanceScheduledMessages(300).scheduledMessages.single()
        assertEquals(ScheduledMessageStatus.READY, ready.status)
        assertEquals("event", ready.eventId)
        assertTrue(ready.payload.contains("Verified output"))
    }

    @Test fun eventAndDeadlineProduceOneWinnerWithEventWinningTie() {
        val p = register(trigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.TASK_SUCCEEDED, taskId = "a", deadline = 200))
        for (at in listOf(199L, 200L, 201L)) {
            val next = p.copy(messageEvents = listOf(event(at = at))).advanceScheduledMessages(300)
            assertEquals(at > 200, next.scheduledMessages.single().timeout)
            assertEquals(next, next.advanceScheduledMessages(1000))
        }
        assertEquals(ScheduledMessageStatus.WAITING, p.advanceScheduledMessages(199).scheduledMessages.single().status)
        assertTrue(p.advanceScheduledMessages(200).scheduledMessages.single().timeout)
    }

    @Test fun stateAlreadyReachedFiresButNewTurnSubscriptionUsesRegistrationCursor() {
        assertEquals(ScheduledMessageStatus.READY,
            register(plan().copy(messageEvents = listOf(event(at = 90)))).advanceScheduledMessages(100).scheduledMessages.single().status)
        val trigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.RESULT_RETURNED, taskId = "a")
        val p = register(plan().copy(messageEvents = listOf(event(MessageEventKind.RESULT_RETURNED, at = 90))), trigger)
        assertEquals(ScheduledMessageStatus.WAITING, p.advanceScheduledMessages(300).scheduledMessages.single().status)
        assertEquals(ScheduledMessageStatus.READY, p.copy(messageEvents = p.messageEvents + event(MessageEventKind.RESULT_RETURNED, id = "new"))
            .advanceScheduledMessages(300).scheduledMessages.single().status)
    }

    @Test fun timerDeliveryCannotRearmItselfEvenWithDifferentTextOrAfterRestart() {
        val p = register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 100), target = null).advanceScheduledMessages(200)
        val restored = Json.decodeFromString<Plan>(Json.encodeToString(Plan.serializer(), p))
        val source = restored.scheduledMessages.single()
        for (at in listOf(200L, 50_000L)) {
            assertFailsWith<IllegalArgumentException> {
                register(restored, MessageTrigger(MessageTriggerKind.AT_TIME, at = at), source.deliveryId, target = null)
            }
        }
        // User-authored schedules and delivery to a worker remain available.
        assertEquals(2, register(restored, MessageTrigger(MessageTriggerKind.AT_TIME, at = 300), "new-user-input", null).scheduledMessages.size)
        assertEquals(2, register(restored, MessageTrigger(MessageTriggerKind.AT_TIME, at = 300), source.deliveryId, "b").scheduledMessages.size)
        assertEquals(2, register(restored, origin = source.deliveryId, target = null).scheduledMessages.size)
    }

    @Test fun scheduledInputCannotSubscribeItselfToAnAlreadyObservedStateEvent() {
        val p = register(plan().copy(messageEvents = listOf(event(at = 90))), target = null).advanceScheduledMessages(100)
        val source = p.scheduledMessages.single()
        assertFailsWith<IllegalArgumentException> { register(p, origin = source.deliveryId, target = null) }
        val awaitingNextResult = register(p, MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.RESULT_RETURNED, taskId = "a"),
            source.deliveryId, target = null)
        assertEquals(ScheduledMessageStatus.WAITING, awaitingNextResult.advanceScheduledMessages(100).scheduledMessages.last().status)
    }

    @Test fun questionAndAttemptSelectorsAreExact() {
        val p = register(trigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.QUESTION_ANSWERED, questionId = "question"))
        assertEquals(ScheduledMessageStatus.WAITING, p.copy(messageEvents = listOf(event(MessageEventKind.QUESTION_ANSWERED, task = null, question = "other")))
            .advanceScheduledMessages(300).scheduledMessages.single().status)
        assertEquals(ScheduledMessageStatus.READY, p.copy(messageEvents = listOf(event(MessageEventKind.QUESTION_ANSWERED, task = null, question = "question")))
            .advanceScheduledMessages(300).scheduledMessages.single().status)
        val a = StageAttempt("attempt", "worker", StageAssignment("model", "m"))
        val source = plan().copy(milestones = plan().milestones.map { if (it.id == "a") it.copy(attempts = listOf(a)) else it })
        val turn = register(source, MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.RESULT_RETURNED, taskId = "a", attemptId = "attempt", turnIndex = 2))
        val wrong = event(MessageEventKind.RESULT_RETURNED).copy(attemptId = "attempt", turnIndex = 1)
        assertEquals(ScheduledMessageStatus.WAITING, turn.copy(messageEvents = listOf(wrong)).advanceScheduledMessages(300).scheduledMessages.single().status)
        assertEquals(ScheduledMessageStatus.READY, turn.copy(messageEvents = listOf(wrong.copy(turnIndex = 2))).advanceScheduledMessages(300).scheduledMessages.single().status)
    }

    @Test fun invalidReferencesAndUnconfirmedRulesAreRejectedBeforePersistence() {
        assertFails { register(trigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.TASK_SUCCEEDED, taskId = "absent")) }
        assertFails { register(target = "absent") }
        assertFails { register(plan().copy(confirmedRevision = null)) }
        assertFails { register(trigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.QUESTION_ANSWERED, questionId = "absent")) }
        assertFails { register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 100, taskId = "a")) }
    }

    @Test fun idsAndCommandReceiptsSurviveSerializationAndCollisionRetries() {
        val p = register()
        val json = Json { encodeDefaults = true }
        val saved = json.decodeFromString<Plan>(json.encodeToString(Plan.serializer(), p))
        assertEquals(saved, register(saved))
        val collision = p.scheduledMessages.single().id
        val ids = ArrayDeque(listOf(collision, "new-rule", "new-rule", "new-delivery"))
        val next = p.applyScheduleCommands(listOf(ScheduleCommand(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 500), text = "Later")),
            "second", "orchestrator", emptySet(), 100, generate = { ids.removeFirst() })
        assertEquals("new-rule", next.scheduledMessages.last().id)
        assertEquals("new-delivery", next.scheduledMessages.last().deliveryId)
        assertEquals(p.scheduledMessages.single(), next.scheduledMessages.first())
    }

    @Test fun rescheduleCancelAndCompletionDoNotRestartRules() {
        val p = register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 200))
        val id = p.scheduledMessages.single().id
        val edited = p.applyScheduleCommands(listOf(ScheduleCommand(ScheduleOperation.UPDATE, id, MessageTrigger(MessageTriggerKind.AT_TIME, at = 400), text = "Changed")), "edit", "orchestrator", emptySet(), 150)
        assertEquals(ScheduledMessageStatus.WAITING, edited.advanceScheduledMessages(200).scheduledMessages.single().status)
        assertEquals(id, edited.scheduledMessages.single().id)
        val cancelled = edited.applyScheduleCommands(listOf(ScheduleCommand(ScheduleOperation.CANCEL, id)), "cancel", "orchestrator", emptySet(), 160)
        assertEquals(ScheduledMessageStatus.CANCELLED, cancelled.advanceScheduledMessages(1000).scheduledMessages.single().status)
        assertEquals(ScheduledMessageStatus.CANCELLED, p.copy(runId = "next").advanceScheduledMessages(300).scheduledMessages.single().status)
        val completion = register(trigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.RUN_COMPLETED))
        assertEquals(ScheduledMessageStatus.READY, completion.copy(phase = ExecutionPhase.COMPLETE, messageEvents = listOf(event(MessageEventKind.RUN_COMPLETED, task = null)))
            .advanceScheduledMessages(300).scheduledMessages.single().status)
        assertEquals(ScheduledMessageStatus.CANCELLED, register().copy(phase = ExecutionPhase.COMPLETE).advanceScheduledMessages(300).scheduledMessages.single().status)
    }

    @Test fun validationChecksInboxReceiptBeforeRuleStatusAndPreservesCommittedCommands() = runTest {
        val store = PlanningStore(JsonPlanningRepository(InMemoryKeyValueStore(), Json { encodeDefaults = true }))
        val original = register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 200)).advanceScheduledMessages(300)
        val rule = original.scheduledMessages.single()
        store.save(original)
        var delivered = true
        var dispatches = 0
        val scheduler = MessageScheduler(store, backgroundScope, { _, _ -> dispatches++; true }, { 300 },
            hasReceipt = { _, _ -> delivered })
        val cancel = listOf(ScheduleCommand(ScheduleOperation.CANCEL, rule.id))
        assertContains(scheduler.validationProblem(original.id, cancel, "cancel", "orchestrator", emptySet())!!,
            "Сообщение уже поставлено в очередь")
        assertFailsWith<IllegalArgumentException> { scheduler.apply(original.id, cancel, "cancel", "orchestrator", emptySet()) }
        assertEquals(original, store.planFor(original.id))
        assertEquals(0, dispatches)

        delivered = false
        val edit = listOf(ScheduleCommand(ScheduleOperation.UPDATE, rule.id,
            MessageTrigger(MessageTriggerKind.AT_TIME, at = 400), text = "Changed"))
        assertNull(scheduler.validationProblem(original.id, edit, "edit", "orchestrator", emptySet()))
        scheduler.apply(original.id, edit, "edit", "orchestrator", emptySet())
        delivered = true
        val committed = store.update(original.id) { it.copy(scheduledMessages = it.scheduledMessages.map { r -> r.copy(status = ScheduledMessageStatus.QUEUED) }) }
        assertNull(scheduler.validationProblem(original.id, edit, "edit", "orchestrator", emptySet()))
        assertEquals(committed, scheduler.apply(original.id, edit, "edit", "orchestrator", emptySet()))
        assertEquals(0, dispatches)
    }

    @Test fun eventsCommitWithSourceStateAndSkippedTaskDoesNotSucceed() = runTest {
        val kv = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true }
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        store.save(plan())
        store.update("plan") { p -> p.copy(milestones = p.milestones.map { if (it.id == "a") it.copy(status = MilestoneStatus.SKIPPED) else it }) }
        assertTrue(store.planFor("plan")!!.messageEvents.isEmpty())
        store.update("plan") { p -> p.copy(milestones = p.milestones.map { if (it.id == "b") it.copy(status = MilestoneStatus.DONE, updatedAt = 200, report = "done") else it }) }
        val reopened = PlanningStore(JsonPlanningRepository(kv, json)).planFor("plan")!!
        assertEquals("b", reopened.messageEvents.single().taskId)
        assertEquals(MessageEventKind.TASK_SUCCEEDED, reopened.messageEvents.single().kind)
        store.update("plan") { it }
        assertEquals(reopened.messageEvents, store.planFor("plan")!!.messageEvents)
    }

    @Test fun pauseRestartAndInterruptedDispatchKeepOneDurableDelivery() = runTest {
        val kv = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true }
        var store = PlanningStore(JsonPlanningRepository(kv, json))
        store.save(register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 200)).copy(intent = ExecutionIntent.PAUSE))
        val inbox = mutableSetOf<String>()
        var calls = 0
        var interrupt = true
        val dispatch: suspend (Plan, ScheduledMessage) -> Boolean = { _, rule ->
            calls++; inbox.add(rule.deliveryId)
            if (interrupt) { interrupt = false; throw CancellationException("Crash after inbox commit") }
            true
        }
        var scheduler = MessageScheduler(store, backgroundScope, dispatch, { 300 })
        scheduler.tick()
        assertEquals(0, calls)
        assertEquals(ScheduledMessageStatus.READY, store.planFor("plan")!!.scheduledMessages.single().status)
        store.update("plan") { it.copy(intent = ExecutionIntent.RUN) }
        assertFailsWith<CancellationException> { scheduler.tick() }
        store = PlanningStore(JsonPlanningRepository(kv, json))
        store.plans()
        scheduler = MessageScheduler(store, backgroundScope, dispatch, { 300 })
        scheduler.tick(); scheduler.tick()
        assertEquals(1, inbox.size)
        assertEquals(2, calls)
        assertEquals(ScheduledMessageStatus.QUEUED, store.planFor("plan")!!.scheduledMessages.single().status)
    }

    @Test fun waitingLoopDoesNotDispatchOrInventEvents() = runTest {
        val store = PlanningStore(JsonPlanningRepository(InMemoryKeyValueStore(), Json { encodeDefaults = true }))
        store.save(register())
        var calls = 0
        val scheduler = MessageScheduler(store, backgroundScope, { _, _ -> calls++; true }, { testScheduler.currentTime })
        scheduler.bootstrap()
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(0, calls)
        assertTrue(store.planFor("plan")!!.messageEvents.isEmpty())
        assertEquals(ScheduledMessageStatus.WAITING, store.planFor("plan")!!.scheduledMessages.single().status)
    }
    @Test fun staleQueuedDeliveryAndDeletedQuestionAreCancelled() {
        val p = register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 100))
        val rule = p.scheduledMessages.single()
        val queued = p.copy(runId = "new-run", scheduledMessages = listOf(rule.copy(status = ScheduledMessageStatus.QUEUED)),
            deliveries = listOf(PlanDelivery(rule.deliveryId, "orchestrator", "b", "Old instruction", sourceRunId = "run")))
        val cancelled = queued.advanceScheduledMessages(300)
        assertEquals(DeliveryState.CANCELLED, cancelled.deliveries.single().state)
        assertEquals(ScheduledMessageStatus.CANCELLED, cancelled.scheduledMessages.single().status)
        val question = register(trigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.QUESTION_ANSWERED, questionId = "question"))
        assertEquals(ScheduledMessageStatus.CANCELLED, question.copy(scheduleQuestionIds = emptySet()).advanceScheduledMessages(300).scheduledMessages.single().status)
    }

    @Test fun checkpointedHandoffIsAnEventBeforeCoordinatorRecovery() {
        val original = plan()
        val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "m"), phase = AttemptPhase.EXECUTING,
            report = """{"kind":"RESULT","text":"Persisted report"}""", coordinationPending = true,
            chatTurns = listOf(StageChatTurn(0, 100, 200)))
        val pending = original.copy(milestones = original.milestones.map { if (it.id == "a") it.copy(attempts = listOf(attempt)) else it })
            .checkpointMessageEvents(original, 200)
        assertEquals(200, pending.messageEvents.single().at)
        val recovered = pending.copy(coordination = listOf(CoordinationRecord("attempt-turn-0", "a", StageReply(StageReplyKind.RESULT, "Persisted report"),
            runId = "run", attemptId = "attempt", sourceSessionId = "worker", createdAt = 300))).checkpointMessageEvents(pending, 300)
        assertEquals(pending.messageEvents, recovered.messageEvents)
    }

    @Test fun relativeTimesAreCalculatedByApplicationAndDoNotMoveAfterRestart() {
        val p = register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, afterMillis = 3_600_000))
        assertEquals(3_600_100L, p.scheduledMessages.single().trigger.at)
        assertNull(p.scheduledMessages.single().trigger.afterMillis)
        assertEquals(p, register(p))
        val timeout = register(trigger = MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.TASK_SUCCEEDED, taskId = "a", timeoutMillis = 60000))
        assertEquals(60100L, timeout.scheduledMessages.single().trigger.deadline)
        assertFails { register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 1, afterMillis = 1)) }
        assertFails { register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, afterMillis = Long.MAX_VALUE)) }
    }

    @Test fun restartAfterCompletionDoesNotFireTimersWhoseDeadlineWasLater() {
        val p = register(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 500))
        val completed = p.copy(phase = ExecutionPhase.COMPLETE, messageEvents = listOf(event(MessageEventKind.RUN_COMPLETED, task = null, at = 200)))
        assertEquals(ScheduledMessageStatus.CANCELLED, completed.advanceScheduledMessages(1000).scheduledMessages.single().status)
    }

    @Test fun legacyResolvedTransferDoesNotBecomePendingAndKeepsItsOriginalRun() {
        val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "m"), phase = AttemptPhase.COMPLETE, turnIndex = 1)
        val task = Milestone("a", "A", attempts = listOf(attempt))
        val p = plan().copy(runId = "new-run", milestones = listOf(task), runHistory = listOf(PlanRunSnapshot("old-run", emptyList(), listOf(task), null, null, 100)))
        val record = CoordinationRecord("attempt-turn-0", "a", StageReply(StageReplyKind.RESULT, "Old result"), CoordinatorReply("Accepted"))
        val view = p.handoffForDisplay(record)
        assertEquals("old-run", view.runId)
        assertEquals("worker", view.sourceSessionId)
        assertEquals(HandoffStatus.RESOLVED, view.status)
        assertEquals(record.id, view.id)
    }

    @Test fun workerCannotWaitForOwnRunCompletionOrDependentWorker() {
        val attempt = StageAttempt("attempt", "worker", StageAssignment("model", "m"))
        val base = plan().copy(milestones = plan().milestones.map { it.copy(attempts = listOf(attempt)) })
        fun wait(p: Plan, task: String, trigger: MessageTrigger) = p.applyScheduleCommands(
            listOf(ScheduleCommand(trigger = trigger, targetTaskId = task, waitTaskId = task, text = "Continue")),
            "wait-$task", "orchestrator", emptySet(), 100, task)
        assertContains(assertFailsWith<IllegalStateException> {
            wait(base, "a", MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.RUN_COMPLETED))
        }.message.orEmpty(), "Цикл ожидания")
        val first = wait(base, "a", MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.TASK_SUCCEEDED, taskId = "b"))
        assertFailsWith<IllegalStateException> {
            wait(first, "b", MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.TASK_SUCCEEDED, taskId = "a"))
        }
        val depends = base.copy(milestones = base.milestones.map { if (it.id == "b") it.copy(dependsOn = listOf("a")) else it })
        assertFailsWith<IllegalStateException> {
            wait(depends, "a", MessageTrigger(MessageTriggerKind.EVENT, event = MessageEventKind.RESULT_RETURNED, taskId = "b"))
        }
        assertNull(first.waitCycleProblem())
    }

    @Test fun staleRunCommandsAreRejectedBeforeCreatingAnyReceipt() = runTest {
        val store = PlanningStore(JsonPlanningRepository(InMemoryKeyValueStore(), Json { encodeDefaults = true }))
        store.save(plan())
        val scheduler = MessageScheduler(store, backgroundScope, { _, _ -> true })
        val failure = assertFailsWith<ScheduleConflict> {
            scheduler.apply("plan", listOf(ScheduleCommand(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 100), text = "Old")),
                "request", "orchestrator", emptySet(), expectedRunId = "previous-run")
        }
        assertEquals(ScheduleConflictCode.STALE_RUN, failure.code)
        assertTrue(store.planFor("plan")!!.scheduleReceipts.isEmpty())
        assertTrue(store.planFor("plan")!!.scheduledMessages.isEmpty())
    }

}
