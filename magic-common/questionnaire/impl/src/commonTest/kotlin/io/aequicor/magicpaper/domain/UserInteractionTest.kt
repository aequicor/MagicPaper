package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import io.aequicor.magicpaper.data.coding.JsonRuntimeQuestionnaireStore
import io.aequicor.magicpaper.data.storage.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UserInteractionTest {
    private val q = PlanningQuestion("q", "Как продолжить?", QuestionKind.SINGLE, listOf(QuestionOption("a", "Первый"), QuestionOption("b", "Второй")))
    private fun request(id: String) = UserInteractionRequest(id, "p", "s", InteractionKind.RUNTIME, listOf(q))

    @Test fun queuePreservesArrivalAndChangesNeitherActiveRequestNorItsPosition() {
        val queue = UserInteractionQueue()
        assertEquals(listOf("a"), queue.reconcile(listOf(request("a"))).map { it.id })
        assertEquals(listOf("a", "b"), queue.reconcile(listOf(request("b"), request("a").copy(context = "Updated"))).map { it.id })
        assertEquals(listOf("b", "c"), queue.reconcile(listOf(request("c"), request("b"), request("a")), setOf("a")).map { it.id })
        assertEquals(listOf("c"), queue.reconcile(listOf(request("c"))).map { it.id })
    }

    @Test fun choicesAndTextAreAdditiveButSkipIsAnExplicitDifferentAnswer() {
        validateInteractionAnswers(listOf(q), listOf(PlanningAnswer("q", listOf("a"), "Комментарий")))
        validateInteractionAnswers(listOf(q), listOf(PlanningAnswer("q", skipped = true)))
        assertContains(interactionAnswerText(listOf(q), listOf(PlanningAnswer("q", skipped = true))), "Пропущено пользователем")
        for (answer in listOf(PlanningAnswer("q"), PlanningAnswer("unknown", listOf("a")), PlanningAnswer("q", listOf("z")),
            PlanningAnswer("q", listOf("a", "b")), PlanningAnswer("q", listOf("a"), skipped = true))) {
            assertFailsWith<IllegalArgumentException> { validateInteractionAnswers(listOf(q), listOf(answer)) }
        }
    }

    @Test fun approvalsCannotBeSkippedOrAnsweredWithTextOrDisabledConsent() {
        val permission = q.copy(allowCustomInput = false, canSkip = false, options = q.options.map { it.copy(enabled = it.id != "a") })
        for (a in listOf(PlanningAnswer("q", skipped = true), PlanningAnswer("q", text = "Да"), PlanningAnswer("q", listOf("a"))))
            assertFailsWith<IllegalArgumentException> { validateInteractionAnswers(listOf(permission), listOf(a)) }
        validateInteractionAnswers(listOf(permission), listOf(PlanningAnswer("q", listOf("b"))))
    }

    @Test fun oldAnswersRemainReadableAndSecretsAreRedactedInHistory() {
        val old = Json.decodeFromString(PlanningAnswer.serializer(), """{"questionId":"q","text":"secret"}""")
        assertFalse(old.skipped)
        assertFalse(interactionAnswerText(listOf(q.copy(secret = true)), listOf(old), true).contains("secret"))
    }

    private fun service(journal: EventJournal = InMemoryEventJournal(), legacy: RuntimeQuestionnaireStore? = null, maxPending: Int = 64) =
        DefaultRuntimeQuestionnaireService(journal, "test", legacy, maxPending)
    private val answer = listOf(PlanningAnswer("q", listOf("a"), "Детали"))

    @Test fun liveQuestionWaitsBeyondNormalTimeoutAndAcceptsExactlyOneReply() = runTest {
        val registry = service()
        val result = async { registry.ask(request("live")) }
        runCurrent(); advanceTimeBy(180_000); runCurrent()
        assertFalse(result.isCompleted)
        assertEquals("live", registry.requests.value.single().id)
        assertFailsWith<IllegalArgumentException> { registry.respond("live", listOf(PlanningAnswer("q"))) }
        registry.respond("live", answer)
        assertFailsWith<IllegalStateException> { registry.respond("live", answer) }
        assertEquals(answer, result.await())
        assertTrue(registry.requests.value.isEmpty())
    }

    @Test fun cancellingOwnerRemovesItsQuestionWithoutAnsweringOtherSessions() = runTest {
        val registry = service()
        val first = launch { registry.ask(request("a")) }
        val second = launch { registry.ask(request("b").copy(sessionId = "other")) }
        runCurrent(); first.cancelAndJoin()
        assertEquals(listOf("b"), registry.requests.value.map { it.id })
        second.cancelAndJoin()
        assertTrue(registry.requests.value.isEmpty())
    }

    @Test fun savedAnswerReplaysOnlyToTheExactCallAfterRestart() = runTest {
        val journal = InMemoryEventJournal()
        val original = service(journal)
        val req = request("durable").copy(runtimeGeneration = 7, runId = "run")
        val waiter = async { original.ask(req) }; runCurrent()
        original.respond(req.id, answer); assertEquals(answer, waiter.await())
        val restored = service(journal)
        assertEquals(answer, restored.ask(req))
        for (changed in listOf(req.copy(runtimeGeneration = 8), req.copy(sessionId = "sibling"),
            req.copy(runId = "other"), req.copy(sourceId = "other"), req.copy(ownerSessionId = "other"),
            req.copy(projectId = "other"), req.copy(questions = listOf(q.copy(title = "Different")))))
            assertFailsWith<IllegalArgumentException> { restored.ask(changed) }
    }

    @Test fun crashBeforeReplyRequiresOriginalCallToReattachAndDoesNotReplayEffects() = runTest {
        val legacy = JsonRuntimeQuestionnaireStore(InMemoryKeyValueStore())
        val req = request("recover").copy(runtimeGeneration = 4, runId = "run")
        legacy.save(listOf(RuntimeQuestionnaireRecord(req)))
        val journal = InMemoryEventJournal()
        val restored = service(journal, legacy).also { it.start() }
        assertEquals(RuntimeQuestionnaireStatus.INTERRUPTED, restored.history.value.single().status)
        assertTrue(restored.requests.value.isEmpty())
        assertFailsWith<IllegalStateException> { restored.respond(req.id, answer) }
        val waiter = async { restored.ask(req) }; runCurrent()
        restored.respond(req.id, answer); assertEquals(answer, waiter.await())
        val replay = service(journal).also { it.start() }
        assertTrue(replay.requests.value.isEmpty())
        assertEquals(RuntimeQuestionnaireStatus.ANSWERED, replay.history.value.single().status)
    }

    @Test fun failedAnswerPersistenceKeepsWaiterAndSupportsSafeRetry() = runTest {
        val journal = FailingJournal()
        val registry = service(journal)
        val waiter = async { registry.ask(request("disk")) }; runCurrent()
        journal.failAnswer = true
        assertFailsWith<IllegalStateException> { registry.respond("disk", answer) }
        assertFalse(waiter.isCompleted)
        assertEquals(RuntimeQuestionnaireStatus.OPEN, registry.history.value.single().status)
        assertEquals("disk", registry.requests.value.single().id)
        assertEquals(QuestionnairePersistence.READY, registry.persistence.value)
        journal.failAnswer = false
        registry.respond("disk", answer); assertEquals(answer, waiter.await())
    }

    @Test fun revocationDoesNotAffectNewGeneration() = runTest {
        val journal = InMemoryEventJournal()
        val registry = service(journal)
        val old = async { registry.ask(request("old").copy(runtimeGeneration = 1)) }
        val fresh = async { registry.ask(request("fresh").copy(runtimeGeneration = 2)) }
        runCurrent(); registry.revoke("s", 1); runCurrent()
        assertTrue(old.isCancelled); assertFalse(fresh.isCompleted)
        assertEquals(RuntimeQuestionnaireStatus.CANCELLED, registry.history.value.first { it.request.id == "old" }.status)
        fresh.cancelAndJoin()
    }

    @Test fun failureAfterCommitUsesExactReadbackWithoutDuplicatingEventOrLosingAnswer() = runTest {
        val journal = FailingJournal()
        val registry = service(journal)
        val req = request("post-commit")
        val waiter = async { registry.ask(req) }; runCurrent()
        journal.failAnswer = true; journal.afterCommit = true
        registry.respond(req.id, answer); assertEquals(answer, waiter.await())
        assertEquals(answer, service(journal.backing).ask(req))
        assertEquals(1, journal.backing.read(journal.backing.streams().single()).count { it.detail.contains("AnswersValidated") })
    }

    @Test fun unreadableOutcomeFreezesMutationsAndCancellationCannotOverwriteCommittedAnswer() = runTest {
        val journal = FailingJournal()
        val registry = service(journal)
        val req = request("uncertain")
        val waiter = async { registry.ask(req) }; runCurrent()
        journal.failAnswer = true; journal.afterCommit = true; journal.failReadAfterWrite = true
        assertFailsWith<IllegalStateException> { registry.respond(req.id, answer) }
        assertFalse(waiter.isCompleted)
        assertEquals(QuestionnairePersistence.UNKNOWN, registry.persistence.value)
        assertTrue(registry.requests.value.single().outcomeUnknown)
        val count = journal.backing.read(journal.backing.streams().single()).size
        assertFailsWith<IllegalArgumentException> { registry.respond(req.id, answer) }
        waiter.cancelAndJoin()
        assertEquals(count, journal.backing.read(journal.backing.streams().single()).size)
        journal.failReadAfterWrite = false; journal.failAnswer = false
        registry.recover()
        assertEquals(QuestionnairePersistence.READY, registry.persistence.value)
        assertTrue(registry.requests.value.isEmpty())
        assertEquals(RuntimeQuestionnaireStatus.ANSWERED, registry.history.value.single().status)
        assertEquals(answer, registry.ask(req))
    }

    @Test fun recoveringUncommittedUnknownWriteReopensOnlyByExplicitReattach() = runTest {
        val journal = FailingJournal()
        val registry = service(journal)
        val req = request("uncommitted")
        val waiter = async { registry.ask(req) }; runCurrent()
        journal.failAnswer = true; journal.failReadAfterWrite = true
        assertFailsWith<IllegalStateException> { registry.respond(req.id, answer) }
        journal.failAnswer = false; journal.failReadAfterWrite = false
        registry.recover(); runCurrent()
        assertTrue(waiter.isCancelled)
        assertEquals(RuntimeQuestionnaireStatus.INTERRUPTED, registry.history.value.single().status)
        assertTrue(registry.requests.value.isEmpty())
        val fresh = async { registry.ask(req) }; runCurrent()
        registry.respond(req.id, answer); assertEquals(answer, fresh.await())
    }

    @Test fun secretsAreAbsentFromEveryJournalInputAndCannotReplayAfterRestart() = runTest {
        val journal = InMemoryEventJournal()
        val registry = service(journal)
        val req = request("secret").copy(questions = listOf(q.copy(secret = true)),
            initialAnswers = listOf(PlanningAnswer("q", text = "initial-private")))
        val waiter = async { registry.ask(req) }; runCurrent()
        val secret = listOf(PlanningAnswer("q", text = "sensitive-token"))
        registry.respond(req.id, secret); assertEquals(secret, waiter.await())
        val dump = journal.read(journal.streams().single()).joinToString { it.detail }
        assertFalse(dump.contains("sensitive-token")); assertFalse(dump.contains("initial-private"))
        val restored = service(journal)
        val reattached = async { restored.ask(req) }; runCurrent()
        assertFalse(reattached.isCompleted)
        reattached.cancelAndJoin()
    }

    @Test fun pendingQuestionBudgetIsReservedBeforePublishing() = runTest {
        val registry = service(maxPending = 1)
        val first = launch { registry.ask(request("first")) }; runCurrent()
        assertFailsWith<IllegalArgumentException> { registry.ask(request("overflow")) }
        assertEquals(listOf("first"), registry.requests.value.map { it.id })
        first.cancelAndJoin()
    }

    @Test fun deliveryAttemptIsDurableBeforeWriteAndUnconfirmedAttemptNeverReplays() = runTest {
        val journal = InMemoryEventJournal()
        val registry = service(journal)
        val req = request("delivery")
        val waiter = async { registry.ask(req) }; runCurrent()
        registry.respond(req.id, answer); waiter.await()
        val attempt = registry.beginDelivery(req.id)
        assertEquals(attempt, registry.history.value.single().deliveryAttemptId)
        val restored = service(journal).also { it.start() }
        assertEquals(RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN, restored.history.value.single().status)
        assertFailsWith<IllegalArgumentException> { restored.ask(req) }
        assertFailsWith<IllegalArgumentException> { restored.beginDelivery(req.id) }
        assertFailsWith<IllegalArgumentException> { restored.finishDelivery(req.id, "another-attempt", QuestionnaireDeliveryOutcome.CONFIRMED) }
        restored.revoke(req.sessionId)
        assertEquals(RuntimeQuestionnaireStatus.DELIVERY_UNKNOWN, restored.history.value.single().status)
        restored.finishDelivery(req.id, attempt, QuestionnaireDeliveryOutcome.CONFIRMED)
        assertEquals(RuntimeQuestionnaireStatus.DELIVERED, restored.history.value.single().status)
        assertFailsWith<IllegalArgumentException> { restored.ask(req) }
    }

    @Test fun legacyStoresImportOnceAndResetCannotResurrectOldAnswers() = runTest {
        for (key in listOf("runtime-questionnaires", "tool-questionnaires")) {
            val kv = InMemoryKeyValueStore()
            val legacy = JsonRuntimeQuestionnaireStore(kv, key)
            val record = RuntimeQuestionnaireRecord(request("legacy"), RuntimeQuestionnaireStatus.ANSWERED, answer)
            legacy.save(listOf(record))
            val journal = InMemoryEventJournal()
            val registry = service(journal, legacy).also { it.start() }
            assertEquals(answer, registry.ask(record.request))
            legacy.save(emptyList())
            assertEquals(answer, service(journal, legacy).ask(record.request))
            legacy.save(listOf(record))
            registry.recover(); registry.clearForReset()
            journal.streams().forEach { journal.drop(it) }
            registry.start()
            assertTrue(legacy.load().isEmpty()); assertTrue(registry.history.value.isEmpty())
        }
    }

    @Test fun failedLegacyReadDoesNotInstallAnEmptyImportMarker() = runTest {
        val journal = InMemoryEventJournal()
        var fail = true
        val legacy = object : RuntimeQuestionnaireStore {
            override fun load(): List<RuntimeQuestionnaireRecord> {
                check(!fail) { "unreadable" }
                return listOf(RuntimeQuestionnaireRecord(request("legacy"), RuntimeQuestionnaireStatus.ANSWERED, answer))
            }
            override fun save(records: List<RuntimeQuestionnaireRecord>) = error("restore must never write legacy")
        }
        val registry = service(journal, legacy)
        assertFailsWith<IllegalStateException> { registry.start() }
        assertTrue(journal.streams().isEmpty())
        assertEquals(QuestionnairePersistence.UNKNOWN, registry.persistence.value)
        fail = false; registry.recover()
        assertEquals(answer, registry.ask(request("legacy")))
    }

    @Test fun factorySharesExactlyOneWriterPerNamespace() {
        val factory = DefaultRuntimeQuestionnaireFactory(InMemoryEventJournal())
        assertSame(factory.create("native", null), factory.create("native", null))
        assertNotSame(factory.create("native", null), factory.create("application-tools", null))
    }


    @Test fun oldWaiterCleanupCannotDetachReplacementAfterRecovery() = runTest {
        val journal = FailingJournal()
        val registry = service(journal)
        val req = request("race")
        val old = async { registry.ask(req) }; runCurrent()
        journal.failAnswer = true; journal.failReadAfterWrite = true
        assertFailsWith<QuestionnairePersistenceException> { registry.respond(req.id, answer) }
        journal.failAnswer = false; journal.failReadAfterWrite = false
        registry.recover()
        // Attach before the canceled coroutine has run its finally block.
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { registry.ask(req) }
        runCurrent()
        assertTrue(old.isCancelled); assertFalse(fresh.isCompleted)
        assertEquals(req.id, registry.requests.value.single().id)
        registry.respond(req.id, answer)
        assertEquals(answer, fresh.await())
    }

    @Test fun applicationFactoryResetClearsAllNativeLegacySourcesBeforeJournalDrop() = runTest {
        val journal = InMemoryEventJournal()
        val factory = DefaultRuntimeQuestionnaireFactory(journal)
        val stores = listOf("pi", "codex", "application-tools").map { namespace ->
            val legacy = JsonRuntimeQuestionnaireStore(InMemoryKeyValueStore())
            legacy.save(listOf(RuntimeQuestionnaireRecord(request(namespace), RuntimeQuestionnaireStatus.ANSWERED, answer)))
            factory.create(namespace, legacy).start()
            legacy
        }
        factory.clearForReset()
        journal.streams().forEach { journal.drop(it) }
        stores.forEach { assertTrue(it.load().isEmpty()) }
        for (namespace in listOf("pi", "codex", "application-tools")) {
            val service = factory.create(namespace, null)
            service.start()
            assertTrue(service.history.value.isEmpty())
        }
    }

    private class FailingJournal(val backing: InMemoryEventJournal = InMemoryEventJournal()) : EventJournal by backing {
        var failAnswer = false
        var afterCommit = false
        var failReadAfterWrite = false
        private var failedWrite = false
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            if (failAnswer && detail.contains("AnswersValidated")) {
                if (afterCommit) backing.append(expected, operation, at, detail)
                failedWrite = true
                error("Injected write failure")
            }
            return backing.append(expected, operation, at, detail)
        }
        override suspend fun snapshot(stream: String): JournalSnapshot {
            check(!(failedWrite && failReadAfterWrite)) { "Injected readback failure" }
            return backing.snapshot(stream)
        }
    }
}
