package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatJournalStoreTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun request(id: String = "request") = CodingRunCheckpoint(id, "private question text", runId = id,
        responseId = "$id:answer", responseTimelineId = "$id:timeline")
    private class Checkpoints : ChatCheckpointStore {
        val values = mutableMapOf<String, ChatSession>()
        var failSave = false
        var saves = 0
        var wipes = 0
        override suspend fun sessions() = values.values.toList()
        override suspend fun session(id: String) = values[id]
        override suspend fun legacySessions(excludingIds: Set<String>) = values.values.filter { it.id !in excludingIds }
        override suspend fun save(session: ChatSession) { check(!failSave) { "Checkpoint unavailable" }; saves++; values[session.id] = session }
        override suspend fun delete(id: String) { values.remove(id) }
        override suspend fun wipe() { wipes++; values.clear() }
    }
    private class Setup(val checkpoints: Checkpoints, val journal: EventJournal, val payloads: ChatPayloadStore, val owner: ChatJournalStore)
    private fun fixture(dispatcher: CoroutineDispatcher, journal: EventJournal = InMemoryEventJournal(),
        checkpoints: Checkpoints = Checkpoints(), values: KeyValueStore = InMemoryKeyValueStore(), payloads: ChatPayloadStore? = null): Setup {
        val privateInputs = payloads ?: StoredChatPayloads(values, json, dispatcher)
        return Setup(checkpoints, journal, privateInputs, ChatJournalStore(checkpoints, journal, privateInputs, json, dispatcher))
    }
    private suspend fun EventJournal.entries() = streams().flatMap { read(it) }
    private suspend fun Setup.create() = owner.dispatch("chat", ChatMachine.Intent.CreateNotebook("chat", 1))

    @Test fun committedInputOwnsTheRunBeforeEffectsAreReturnedAndPrivateTextStaysOutOfTheJournal() = runTest {
        val f = fixture(UnconfinedTestDispatcher(testScheduler))
        f.create()
        val accepted = f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2))
        assertIs<ChatMachine.Effect.RunRequest>(accepted.effects.last())
        assertEquals(request(), f.owner.session("chat")?.pendingRun)
        val last = f.journal.entries().last()
        assertContains(last.detail, "Submit")
        assertFalse(last.detail.contains("private question text"))
        assertEquals(request(), f.checkpoints.values.getValue("chat").pendingRun)
    }

    @Test fun exactLostAppendAcknowledgementPublishesTheAlreadyCommittedInputOnce() = runTest {
        val backing = InMemoryEventJournal()
        var thrown = false
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if (detail.contains("Submit") && !thrown) { thrown = true; error("Lost acknowledgement") }
                return record
            }
        }
        val f = fixture(UnconfinedTestDispatcher(testScheduler), journal)
        f.create()
        val accepted = f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2))
        assertEquals(1, accepted.effects.filterIsInstance<ChatMachine.Effect.RunRequest>().size)
        assertEquals(1, backing.entries().count { it.detail.contains("Submit") })
        assertEquals(listOf("request"), f.owner.session("chat")?.messages?.map { it.id })
    }

    @Test fun uncertainAcknowledgementReturnsNoRunEffectAndRestartCannotAutomaticallyRunIt() = runTest {
        val backing = InMemoryEventJournal()
        var unreadable = false
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if (detail.contains("Submit")) { unreadable = true; error("Lost acknowledgement") }
                return record
            }
            override suspend fun snapshot(stream: String): JournalSnapshot {
                check(!unreadable) { "Storage unavailable" }
                return backing.snapshot(stream)
            }
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val f = fixture(dispatcher, journal)
        f.create()
        assertFailsWith<IllegalStateException> { f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2)) }
        assertTrue(checkNotNull(f.owner.stateFor("chat")).persistenceUnknown)
        assertTrue("chat" in f.owner.failures.value)
        val restored = fixture(dispatcher, backing, f.checkpoints, payloads = f.payloads)
        restored.owner.start()
        assertEquals(ChatMachine.Phase.UNKNOWN, restored.owner.stateFor("chat")?.runs?.get("chat")?.phase)
        assertEquals(1, backing.entries().count { it.detail.contains("Submit") })
    }

    @Test fun checkpointFailureIsObservableAndJournalStillRebuildsTheAcceptedHistory() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        f.create()
        f.checkpoints.failSave = true
        val transition = f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2))
        assertTrue(transition.effects.any { it is ChatMachine.Effect.RunRequest })
        assertTrue("chat" in f.owner.failures.value)
        f.checkpoints.failSave = false
        val restored = fixture(dispatcher, f.journal, f.checkpoints, payloads = f.payloads)
        restored.owner.start()
        assertEquals("private question text", restored.owner.session("chat")?.messages?.single()?.text)
        assertEquals(ChatMachine.Phase.UNKNOWN, restored.owner.stateFor("chat")?.runs?.get("chat")?.phase)
    }

    @Test fun legacyImportOccursOnceAndLaterCheckpointChangesCannotReplaceJournalHistory() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val checkpoints = Checkpoints().apply { values["chat"] = ChatSession("chat", "Original", 1, 1,
            messages = listOf(ChatMessage("original", ChatRole.USER, "preserved", 1))) }
        val f = fixture(dispatcher, checkpoints = checkpoints)
        f.owner.start()
        assertEquals(1, f.journal.entries().count { it.detail.contains("LegacyImported") })
        checkpoints.values["chat"] = checkpoints.values.getValue("chat").copy(messages = emptyList())
        val restored = fixture(dispatcher, f.journal, checkpoints, payloads = f.payloads)
        restored.owner.start()
        assertEquals("preserved", restored.owner.session("chat")?.messages?.single()?.text)
        assertEquals(1, f.journal.entries().count { it.detail.contains("LegacyImported") })
    }

    @Test fun mismatchedAggregateIdentityIsRejectedBeforeAnyPayloadOrJournalWrite() = runTest {
        val values = InMemoryKeyValueStore()
        val f = fixture(UnconfinedTestDispatcher(testScheduler), values = values)
        assertFailsWith<IllegalStateException> { f.owner.dispatch("chat", ChatMachine.Intent.CreateNotebook("other", 1)) }
        assertTrue(f.journal.entries().isEmpty())
        assertTrue(values.keys("chat-input:").isEmpty())
        assertTrue(f.owner.states.value.isEmpty())
    }

    private fun importedNotebook(id: String, childId: String) = ChatMachine.Intent.ImportNotebook(listOf(
        ChatSession(id, id, 1, 1), ChatSession(childId, childId, 1, 1, researchParentId = id)))

    @Test fun importedChildAndNotebookIdentitiesCannotBelongToAnotherAggregate() = runTest {
        val values = InMemoryKeyValueStore()
        val f = fixture(UnconfinedTestDispatcher(testScheduler), values = values)
        f.owner.dispatch("a", importedNotebook("a", "shared"))
        val records = f.journal.entries()
        val payloadKeys = values.keys("chat-input:")
        for (input in listOf(importedNotebook("b", "shared"), importedNotebook("b", "a"))) {
            assertFailsWith<ChatCommandRejected> { f.owner.dispatch("b", input) }
        }
        assertFailsWith<ChatCommandRejected> { f.owner.dispatch("shared", ChatMachine.Intent.CreateNotebook("shared", 2)) }
        assertEquals(records, f.journal.entries())
        assertEquals(payloadKeys, values.keys("chat-input:"))
        assertEquals("a", f.owner.stateFor("shared")?.notebookId)
        assertEquals(setOf("a"), f.owner.states.value.keys)
    }

    @Test fun deletedQuestionAndNotebookIdsRemainReservedAcrossRestart() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        f.owner.dispatch("a", importedNotebook("a", "shared"))
        f.owner.dispatch("a", ChatMachine.Intent.Delete("shared"))
        f.owner.dispatch("a", ChatMachine.Intent.Delete("a"))
        val restored = fixture(dispatcher, f.journal, f.checkpoints, payloads = f.payloads)
        restored.owner.start()
        restored.owner.dispatch("b", ChatMachine.Intent.CreateNotebook("b", 2))
        val records = f.journal.entries()
        for (id in listOf("a", "shared")) {
            assertFailsWith<ChatCommandRejected> { restored.owner.dispatch("b", ChatMachine.Intent.CreateQuestion(id, "b", 3)) }
            assertFails { restored.owner.dispatch(id, ChatMachine.Intent.CreateNotebook(id, 3)) }
            assertEquals("a", restored.owner.stateFor(id)?.notebookId)
        }
        assertEquals(records, f.journal.entries())
    }

    @Test fun uncertainInitialWriteReservesItsRootKeyAndHasAnObservableFailureState() = runTest {
        val backing = InMemoryEventJournal()
        var attempts = 0
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                attempts++
                error("Initial write outcome is unknown")
            }
        }
        val f = fixture(UnconfinedTestDispatcher(testScheduler), journal)
        assertFails { f.create() }
        assertTrue(checkNotNull(f.owner.stateFor("chat")).persistenceUnknown)
        assertFailsWith<ChatCommandRejected> { f.owner.dispatch("other", importedNotebook("other", "chat")) }
        assertEquals(1, attempts)
        assertNull(f.owner.stateFor("other"))
    }

    @Test fun replayDetectsSharedIdentityBeforeAnyRestoreWriteOrStatePublication() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val a = fixture(dispatcher)
        val b = fixture(dispatcher)
        a.owner.dispatch("a", importedNotebook("a", "shared"))
        a.owner.dispatch("a", ChatMachine.Intent.Submit("shared", request(), 2))
        b.owner.dispatch("b", importedNotebook("b", "shared"))
        var writes = 0
        val streamsA = a.journal.streams().toSet()
        val journal = object : EventJournal by a.journal {
            override suspend fun streams() = a.journal.streams() + b.journal.streams()
            override suspend fun snapshot(stream: String) = if (stream in streamsA) a.journal.snapshot(stream) else b.journal.snapshot(stream)
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                writes++; error("Conflicting restore cannot append")
            }
        }
        val payloads = object : ChatPayloadStore by a.payloads {
            override suspend fun read(ref: ChatInputRef) = if (ref.notebookId == "a") a.payloads.read(ref) else b.payloads.read(ref)
            override suspend fun save(notebookId: String, inputId: String, input: ChatMachine.Input): ChatInputRef {
                writes++; error("Conflicting restore cannot write a payload")
            }
        }
        val restored = fixture(dispatcher, journal, payloads = payloads)
        assertFailsWith<IllegalStateException> { restored.owner.start() }
        assertEquals(0, writes)
        assertTrue(restored.owner.states.value.isEmpty())
        assertTrue(restored.checkpoints.values.isEmpty())
    }

    @Test fun missingAndCorruptPrivatePayloadsFailClosedWithoutReplacingCheckpoints() = runTest {
        for (missing in listOf(false, true)) {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val values = InMemoryKeyValueStore()
            val f = fixture(dispatcher, values = values)
            f.create()
            f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2))
            val key = values.keys("chat-input:").last()
            if (missing) values.delete(key) else values.write(key, "{broken private payload")
            val checkpoint = f.checkpoints.values.toMap()
            val entries = f.journal.entries()
            val restored = fixture(dispatcher, f.journal, f.checkpoints, values)
            assertFails { restored.owner.start() }
            assertEquals(checkpoint, f.checkpoints.values)
            assertEquals(entries, f.journal.entries())
            assertTrue(restored.owner.states.value.isEmpty())
        }
    }

    @Test fun journalIdentitySequenceGenerationAndHighWaterAreVerifiedBeforePayloadRecovery() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        f.create()
        f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2))
        val original = f.journal.snapshot(f.journal.streams().single())
        val first = original.records.first()
        val faults = listOf(
            original.copy(revision = original.revision.copy(stream = "another-owner")),
            original.copy(records = original.records.mapIndexed { index, record -> if (index == 0) record.copy(stream = "another-owner") else record }),
            original.copy(records = original.records.reversed()),
            original.copy(records = original.records.mapIndexed { index, record -> if (index == 1) record.copy(seq = first.seq) else record }),
            original.copy(revision = original.revision.copy(seq = original.revision.seq + 1)),
            original.copy(revision = original.revision.copy(resetEpoch = original.revision.resetEpoch + 1)),
        )
        for (fault in faults) {
            val journal = object : EventJournal by f.journal {
                override suspend fun snapshot(stream: String) = fault
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? = error("Corrupt recovery cannot append")
            }
            val restored = fixture(dispatcher, journal, f.checkpoints, payloads = f.payloads)
            assertFails { restored.owner.start() }
            assertTrue(restored.owner.states.value.isEmpty())
        }
    }

    @Test fun lostAcknowledgementCannotBeProvenByAnEnvelopeFromAnotherStream() = runTest {
        val backing = InMemoryEventJournal()
        var lost = false
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if (detail.contains("Submit")) { lost = true; error("Lost acknowledgement") }
                return record
            }
            override suspend fun snapshot(stream: String): JournalSnapshot = backing.snapshot(stream).let {
                if (lost) it.copy(revision = it.revision.copy(stream = "another-owner")) else it
            }
        }
        val f = fixture(UnconfinedTestDispatcher(testScheduler), journal)
        f.create()
        assertFails { f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2)) }
        assertTrue(checkNotNull(f.owner.stateFor("chat")).persistenceUnknown)
    }

    @Test fun lostAcknowledgementRequiresTheEntirePreviouslyVerifiedPrefix() = runTest {
        for (removePrefix in listOf(false, true)) {
            val backing = InMemoryEventJournal()
            var lost = false
            val journal = object : EventJournal by backing {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                    val record = backing.append(expected, operation, at, detail)
                    if (detail.contains("Submit")) { lost = true; error("Lost acknowledgement") }
                    return record
                }
                override suspend fun snapshot(stream: String): JournalSnapshot = backing.snapshot(stream).let { snapshot ->
                    if (!lost) snapshot else snapshot.copy(records = if (removePrefix) listOf(snapshot.records.last())
                        else snapshot.records.mapIndexed { index, record -> if (index == 0) record.copy(at = record.at + 1) else record })
                }
            }
            val f = fixture(UnconfinedTestDispatcher(testScheduler), journal)
            f.create()
            assertFails { f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2)) }
            assertTrue(checkNotNull(f.owner.stateFor("chat")).persistenceUnknown)
            assertNull(f.checkpoints.values.getValue("chat").pendingRun)
        }
    }

    @Test fun exactLostAcknowledgementStillRequiresTheNewPrivatePayloadProof() = runTest {
        for (missing in listOf(false, true)) {
            val values = InMemoryKeyValueStore()
            val backing = InMemoryEventJournal()
            val journal = object : EventJournal by backing {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                    val record = backing.append(expected, operation, at, detail)
                    if (detail.contains("Submit")) {
                        val key = values.keys("chat-input:").single { values.read(it)?.contains("Submit") == true }
                        if (missing) values.delete(key) else values.write(key, "{corrupted private payload")
                        error("Lost acknowledgement")
                    }
                    return record
                }
            }
            val f = fixture(UnconfinedTestDispatcher(testScheduler), journal, values = values)
            f.create()
            assertFails { f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2)) }
            assertTrue(checkNotNull(f.owner.stateFor("chat")).persistenceUnknown)
            assertNull(f.checkpoints.values.getValue("chat").pendingRun)
        }
    }

    @Test fun anEmptyJournalStillRequiresANonnegativeSequenceAndResetGeneration() = runTest {
        for (negativeEpoch in listOf(false, true)) {
            val backing = InMemoryEventJournal()
            val journal = object : EventJournal by backing {
                override suspend fun snapshot(stream: String) = JournalSnapshot(JournalRevision(stream,
                    if (negativeEpoch) 0 else -1, if (negativeEpoch) -1 else 0), emptyList())
            }
            val values = InMemoryKeyValueStore()
            val f = fixture(UnconfinedTestDispatcher(testScheduler), journal, values = values)
            assertFails { f.create() }
            assertTrue(values.keys("chat-input:").isEmpty())
            assertTrue(f.owner.states.value.isEmpty())
            assertTrue(backing.entries().isEmpty())
        }
    }

    @Test fun globalSequenceGapsFromOtherOwnersAreValid() = runTest {
        val backing = InMemoryEventJournal()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                backing.append("media-other", "another-owner", at)
                return backing.append(expected, operation, at, detail)
            }
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val f = fixture(dispatcher, journal)
        f.create()
        f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2))
        val restored = fixture(dispatcher, journal, f.checkpoints, payloads = f.payloads)
        restored.owner.start()
        assertEquals("private question text", restored.owner.session("chat")?.messages?.single()?.text)
    }

    @Test fun wipeDrainsAdmittedCommitAndCanceledWaitingCommandsCannotResurrectOldData() = runTest {
        val backing = InMemoryEventJournal()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (detail.contains("Submit")) { entered.complete(Unit); release.await() }
                return backing.append(expected, operation, at, detail)
            }
        }
        val values = InMemoryKeyValueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = fixture(dispatcher, journal, values = values)
        f.create()
        val admitted = launch { f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request(), 2)) }
        runCurrent(); entered.await()
        val waiting = launch { f.owner.dispatch("chat", ChatMachine.Intent.Submit("chat", request("queued"), 3)) }
        runCurrent()
        waiting.cancelAndJoin()
        val wipe = launch { f.owner.wipe() }
        runCurrent()
        assertFalse(wipe.isCompleted)
        release.complete(Unit)
        admitted.join(); wipe.join()
        assertTrue(f.owner.states.value.isEmpty())
        assertTrue(backing.entries().isEmpty())
        assertTrue(values.keys("chat-input:").isEmpty())
        assertTrue(f.checkpoints.values.isEmpty())
        f.create()
        assertNotNull(f.owner.session("chat"))
        assertTrue(checkNotNull(f.owner.session("chat")).messages.isEmpty())
    }

    @Test fun failedCheckpointWipeRetainsJournalAndPrivatePayloadAuthority() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val f = fixture(dispatcher)
        f.create()
        val records = f.journal.entries()
        val checkpoints = object : ChatCheckpointStore by f.checkpoints {
            override suspend fun wipe(): Unit = error("Checkpoint deletion failed")
        }
        val owner = ChatJournalStore(checkpoints, f.journal, f.payloads, json, dispatcher)
        owner.start()
        assertFailsWith<IllegalStateException> { owner.wipe() }
        assertEquals(records, f.journal.entries())
        assertNotNull(owner.session("chat"))
    }

    @Test fun privatePayloadWriteThenThrowIsProvedByExactReadbackAndIdentitiesAreImmutable() = runTest {
        val backing = InMemoryKeyValueStore()
        var writes = 0
        val values = object : KeyValueStore by backing {
            override fun write(key: String, value: String) { writes++; backing.write(key, value); error("Lost acknowledgement") }
        }
        val payloads = StoredChatPayloads(values, json, UnconfinedTestDispatcher(testScheduler))
        val input = ChatMachine.Intent.CreateNotebook("chat", 1)
        val ref = payloads.save("chat", "input", input)
        assertEquals(input, payloads.read(ref))
        assertEquals(ref, payloads.save("chat", "input", input))
        assertEquals(1, writes)
        assertFails { payloads.save("chat", "input", ChatMachine.Intent.CreateNotebook("chat", 2)) }
        assertEquals(input, payloads.read(ref))
    }

    @Test fun payloadKindDigestAndOwnerAreAllPartOfItsProof() = runTest {
        val backing = InMemoryKeyValueStore()
        val payloads = StoredChatPayloads(backing, json, UnconfinedTestDispatcher(testScheduler))
        val ref = payloads.save("chat", "input", ChatMachine.Intent.CreateNotebook("chat", 1))
        assertFails { payloads.read(ref.copy(kind = "Submit")) }
        assertFails { payloads.read(ref.copy(digest = "wrong")) }
        assertFails { payloads.read(ref.copy(notebookId = "other")) }
        assertFails { payloads.read(ref.copy(inputId = "other")) }
        val key = backing.keys("chat-input:").single()
        backing.write(key, backing.read(key)!!.replace("CreateNotebook", "Submit"))
        assertFails { payloads.read(ref) }
    }
}
