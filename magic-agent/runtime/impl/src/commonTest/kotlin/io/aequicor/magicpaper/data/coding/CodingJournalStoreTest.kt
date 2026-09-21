package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class CodingJournalStoreTest {
    private class FaultJournal(val backing: EventJournal = InMemoryEventJournal()) : EventJournal by backing {
        var loseAck = false
        var cancelAck = false
        var corruptAckTime = false
        var corruptRead: ((JournalSnapshot) -> JournalSnapshot)? = null
        var replacePrefixAfterAppend = false
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            val record = backing.append(expected, operation, at, detail)
            if (replacePrefixAfterAppend) {
                replacePrefixAfterAppend = false
                corruptRead = { it.copy(records = it.records.mapIndexed { index, row -> if (index == 0) row.copy(at = row.at + 1) else row }) }
            }
            if (cancelAck) { cancelAck = false; throw CancellationException("cancelled after append") }
            if (loseAck) { loseAck = false; error("lost acknowledgement") }
            return if (corruptAckTime) record?.copy(at = at + 1) else record
        }
        override suspend fun snapshot(stream: String) = backing.snapshot(stream).let { corruptRead?.invoke(it) ?: it }
    }
    private class Fixture(val storage: InMemoryKeyValueStore = InMemoryKeyValueStore(), val journal: FaultJournal = FaultJournal()) {
        val json = Json { encodeDefaults = true }
        val payloads = StoredCodingPayloads(storage, json, Dispatchers.Unconfined)
        fun open() = CodingJournalStore(JsonCodingProjectRepository(storage, json), journal, payloads, json, Dispatchers.Unconfined)
        val owner = open()
        suspend fun create(id: String = "project") = owner.dispatch(id, CodingMachine.Intent.CreateProject(CodingProject(id, "Project", "/fixture", 1)))
        suspend fun add(id: String = "session", projectId: String = "project") = owner.dispatch(projectId,
            CodingMachine.Intent.CreateSession(CodingSession(id, projectId, "Session", 1, engine = CodingEngine.PI)))
        suspend fun size(id: String = "project") = journal.backing.read(CodingJournalStore.stream(id)).size
    }
    @Test fun exactLostAcknowledgementCommitsOneInputAndRestartsWithoutEffects() = runTest {
        val f = Fixture(); f.create(); f.journal.loseAck = true
        f.add()
        assertEquals(2, f.size())
        val restarted = f.open(); restarted.start()
        assertEquals(listOf("session"), restarted.sessions("project").map { it.id })
        assertEquals(2, f.size())
    }
    @Test fun replacedPrefixCannotConfirmAnOtherwiseMatchingLastRecord() = runTest {
        val f = Fixture(); f.create(); f.journal.loseAck = true; f.journal.replacePrefixAfterAppend = true
        assertFails { f.add() }
        assertTrue(f.owner.states.value.getValue("project").persistenceUnknown)
        assertTrue(f.owner.failures.value.isNotEmpty())
        assertFails { f.add("other") }
    }
    @Test fun cancelledUncertainAppendFencesAuthorityAndPreservesCancellation() = runTest {
        val f = Fixture(); f.create(); f.journal.cancelAck = true
        assertFailsWith<CancellationException> { f.add() }
        assertTrue(f.owner.states.value.getValue("project").persistenceUnknown)
        assertTrue(f.owner.failures.value.isNotEmpty())
        assertFails { f.add("other") }
        assertEquals(2, f.size())
    }
    @Test fun alteredAcknowledgementTimestampMustBeVerifiedAgainstReadback() = runTest {
        val f = Fixture(); f.create(); f.journal.corruptAckTime = true
        f.add()
        // A mismatching direct acknowledgement can only succeed through exact committed readback.
        f.journal.corruptAckTime = false; f.journal.loseAck = true
        f.add("next")
        assertEquals(listOf("session", "next"), f.open().sessions("project").map { it.id })
    }
    @Test fun resetInvalidatesAStaleWriterInsteadOfAcceptingANewChild() = runTest {
        val f = Fixture(); f.create()
        f.journal.drop(CodingJournalStore.stream("project"))
        assertFails { f.add() }
        assertTrue(f.owner.states.value.getValue("project").persistenceUnknown)
        assertEquals(0, f.size())
    }
    @Test fun corruptPrivatePayloadFailsBeforeAnyStatePublication() = runTest {
        val f = Fixture(); f.create(); f.add()
        val payloadKey = f.storage.keys("").first { it.startsWith("coding-input:") }
        f.storage.write(payloadKey, "{}")
        val restarted = f.open()
        assertFails { restarted.start() }
        assertTrue(restarted.states.value.isEmpty())
        assertEquals(2, f.size())
    }
    @Test fun wrongStreamSequenceEpochAndNegativeRevisionNeverPublish() = runTest {
        val f = Fixture(); f.create(); f.add()
        val corruptions: List<(JournalSnapshot) -> JournalSnapshot> = listOf(
            { it.copy(records = it.records.map { row -> row.copy(stream = "other") }) },
            { it.copy(records = it.records.reversed()) },
            { it.copy(revision = it.revision.copy(resetEpoch = it.revision.resetEpoch + 1)) },
            { it.copy(revision = it.revision.copy(seq = -1)) },
        )
        for (corrupt in corruptions) {
            f.journal.corruptRead = corrupt
            val restarted = f.open(); assertFails { restarted.start() }; assertTrue(restarted.states.value.isEmpty())
        }
    }
    @Test fun sessionIdentityAndDeletedSessionStayReservedAcrossProjectsAndRestart() = runTest {
        val f = Fixture(); f.create("a"); f.add("shared", "a"); f.create("b")
        val before = f.size("b")
        assertFails { f.add("shared", "b") }
        f.owner.dispatch("a", CodingMachine.Intent.DeleteSession(CodingMachine.SessionRef("shared", 0)))
        val reopened = f.open()
        assertFails { reopened.dispatch("b", CodingMachine.Intent.CreateSession(CodingSession("shared", "b", "Other", 1, engine = CodingEngine.PI))) }
        assertEquals(before, f.size("b"))
    }
    @Test fun inputCollectionsCannotMutateAnAlreadyCommittedProjection() = runTest {
        val f = Fixture()
        val message = CodingMessage("message", CodingRole.USER, "Saved text", createdAt = 1)
        val history = mutableListOf(message)
        val input = CodingMachine.Fact.LegacyImported(CodingProject("project", "Project", "/fixture", 1),
            listOf(CodingSession("session", "project", "Session", 1, engine = CodingEngine.PI)), mapOf("session" to history))
        f.owner.dispatch("project", input)
        history.clear()
        assertEquals(listOf(message), f.owner.messages("project", "session"))
        assertEquals(listOf(message), f.open().messages("project", "session"))
    }
    @Test fun explicitWipeRemovesCachesAndInputsBeforeNewOwnerReuse() = runTest {
        val f = Fixture(); f.create(); f.add(); f.owner.wipe()
        assertTrue(f.owner.states.value.isEmpty()); assertTrue(f.journal.streams().isEmpty())
        assertTrue(f.storage.keys("coding-input:").isEmpty())
        f.create(); f.add()
        assertEquals(listOf("session"), f.open().sessions("project").map { it.id })
    }
    @Test fun malformedPayloadProofCannotPublishAuthorityAfterNormalAppend() = runTest {
        for (foreignOwner in listOf(true, false)) {
            val f = Fixture()
            val wrong = object : CodingPayloadStore by f.payloads {
                override suspend fun save(projectId: String, inputId: String, input: CodingMachine.Input): CodingInputRef {
                    val actual = f.payloads.save(projectId, inputId, input)
                    return if (foreignOwner) actual.copy(projectId = "foreign") else actual.copy(digest = "corrupt")
                }
            }
            val owner = CodingJournalStore(JsonCodingProjectRepository(f.storage, f.json), f.journal, wrong, f.json, Dispatchers.Unconfined)
            assertFails { owner.dispatch("project", CodingMachine.Intent.CreateProject(CodingProject("project", "Project", "/fixture", 1))) }
            assertTrue(owner.states.value.getValue("project").persistenceUnknown)
            assertTrue(owner.all().isEmpty())
        }
    }

}
