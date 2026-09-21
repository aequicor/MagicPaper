package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillMachine
import io.ktor.util.Digest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

class SkillJournalIntegrityTest {
    private val json = skillOwnerTestJson

    @Test fun malformedDirectAcknowledgementsCannotPublishTheRequestedChange() = runTest {
        val corruptions: List<(JournalRecord) -> JournalRecord> = listOf(
            { it.copy(stream = "another-owner") }, { it.copy(seq = 0) },
            { it.copy(at = it.at + 1) }, { it.copy(operation = "another.input") }, { it.copy(detail = "{}") },
        )
        for (corrupt in corruptions) {
            val store = InMemoryKeyValueStore()
            val events = SkillFaultJournal()
            val owner = SkillInputJournal(store, events, json)
            owner.restore()
            val before = owner.state
            events.acknowledgement = corrupt
            assertFails { owner.commit(install(owner, skillOwnerFixture("a"))) }
            assertEquals(before.skills, owner.state.skills)
            assertTrue(owner.state.persistenceUnknown)
            assertEquals(2, events.read(SkillInputJournal.STREAM).size)
        }
    }

    @Test fun anExactLostAcknowledgementPublishesOnceAndReopensWithoutRepeatingTheInput() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillInputJournal(store, events, json)
        owner.restore()
        events.appendFailure = IllegalStateException("lost acknowledgement")
        owner.commit(install(owner, skillOwnerFixture("a")))
        assertFalse(owner.state.persistenceUnknown)
        assertEquals(setOf("a"), owner.state.skills.keys)
        val records = events.read(SkillInputJournal.STREAM)
        assertEquals(2, records.size)
        val reopened = SkillInputJournal(store, events, json)
        reopened.restore()
        assertEquals(owner.state, reopened.state)
        assertEquals(records, events.read(SkillInputJournal.STREAM))
    }

    @Test fun lostAcknowledgementRequiresTheExactPrefixNotOnlyTheLastInput() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillInputJournal(store, events, json)
        owner.restore()
        val before = owner.state
        events.afterAppend = {
            events.snapshotTransform = { snapshot -> snapshot.copy(records = snapshot.records.mapIndexed { index, record ->
                if (index == 0) record.copy(at = record.at + 1) else record
            }) }
        }
        events.appendFailure = IllegalStateException("lost acknowledgement")
        assertFails { owner.commit(install(owner, skillOwnerFixture("a"))) }
        assertEquals(before.skills, owner.state.skills)
        assertTrue(owner.state.persistenceUnknown)
    }

    @Test fun missingOrChangedPrivatePayloadCannotBeHiddenByAnExactAppendReceipt() = runTest {
        for (remove in listOf(false, true)) {
            val store = InMemoryKeyValueStore()
            val events = SkillFaultJournal()
            val owner = SkillInputJournal(store, events, json)
            owner.restore()
            events.afterAppend = { record ->
                val key = skillPayloadKey(record)
                if (remove) store.delete(key)
                else store.write(key, checkNotNull(store.read(key)).replace("private instruction", "changed instruction"))
            }
            events.appendFailure = IllegalStateException("lost acknowledgement")
            assertFails { owner.commit(install(owner, skillOwnerFixture("a", instructions = "private instruction"))) }
            assertTrue(owner.state.skills.isEmpty())
            assertTrue(owner.state.persistenceUnknown)
            assertTrue(events.read(SkillInputJournal.STREAM).none { "private instruction" in it.detail })
            assertFails { SkillInputJournal(store, events, json).restore() }
        }
    }

    @Test fun envelopeOwnerAndEpochMustMatchTheObservedStream() = runTest {
        for (field in listOf("owner", "resetEpoch")) {
            val store = InMemoryKeyValueStore()
            val events = SkillFaultJournal()
            SkillInputJournal(store, events, json).restore()
            val before = events.read(SkillInputJournal.STREAM)
            events.snapshotTransform = { snapshot -> snapshot.copy(records = snapshot.records.map { record ->
                val changed = json.parseToJsonElement(record.detail).jsonObject.toMutableMap()
                changed[field] = if (field == "owner") JsonPrimitive("foreign-owner") else JsonPrimitive(1)
                record.copy(detail = JsonObject(changed).toString())
            }) }
            val reopened = SkillInputJournal(store, events, json)
            assertFails { reopened.restore() }
            assertFalse(reopened.state.initialized)
            assertEquals(before, events.read(SkillInputJournal.STREAM))
        }
    }

    @Test fun validDigestCannotHideForeignPrivatePayloadOwnerEpochOrIdentity() = runTest {
        for (field in listOf("owner", "resetEpoch", "id")) {
            val store = InMemoryKeyValueStore()
            val events = SkillFaultJournal()
            SkillInputJournal(store, events, json).restore()
            val original = events.read(SkillInputJournal.STREAM).single()
            val key = skillPayloadKey(original)
            val changed = json.parseToJsonElement(checkNotNull(store.read(key))).jsonObject.toMutableMap()
            changed[field] = if (field == "resetEpoch") JsonPrimitive(1) else JsonPrimitive("foreign")
            val raw = JsonObject(changed).toString()
            store.write(key, raw)
            val hasher = Digest("SHA-256")
            hasher += raw.encodeToByteArray()
            val digest = hasher.build().toHexString()
            val envelope = json.parseToJsonElement(original.detail).jsonObject.toMutableMap()
            envelope["digest"] = JsonPrimitive(digest)
            events.snapshotTransform = { it.copy(records = listOf(original.copy(detail = JsonObject(envelope).toString()))) }
            assertFails { SkillInputJournal(store, events, json).restore() }
            assertEquals(raw, store.read(key), "Corruption must not be replaced with an empty owner payload")
        }
    }

    @Test fun aChangedSnapshotEpochCannotRebrandValidOldPayloads() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillInputJournal(store, events, json)
        owner.restore()
        owner.commit(install(owner, skillOwnerFixture("a")))
        val before = events.read(SkillInputJournal.STREAM)
        events.snapshotTransform = { it.copy(revision = it.revision.copy(resetEpoch = 1)) }
        assertFails { SkillInputJournal(store, events, json).restore() }
        assertEquals(before, events.read(SkillInputJournal.STREAM))
    }

    @Test fun cancellationWithExactReadbackKeepsTheConfirmedCommitAndRemainsCancellation() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillInputJournal(store, events, json)
        owner.restore()
        val cancelled = CancellationException("controlled append cancellation")
        events.appendFailure = cancelled
        val failure = assertFailsWith<CancellationException> { owner.commit(install(owner, skillOwnerFixture("a"))) }
        assertTrue(skillFailureCauses(failure).any { it === cancelled })
        assertFalse(owner.state.persistenceUnknown)
        assertEquals(setOf("a"), owner.state.skills.keys)
        val reopened = SkillInputJournal(store, events, json)
        reopened.restore()
        assertEquals(owner.state, reopened.state)
        assertEquals(2, events.read(SkillInputJournal.STREAM).size)
    }

    @Test fun cancellationStaysPrimaryWhenRevisionPayloadOrReadbackProofFails() = runTest {
        for (fault in listOf("revision", "payload", "read")) {
            val store = InMemoryKeyValueStore()
            val events = SkillFaultJournal()
            val owner = SkillInputJournal(store, events, json)
            owner.restore()
            val cancelled = CancellationException("controlled append cancellation")
            events.afterAppend = { record ->
                when (fault) {
                    "revision" -> events.snapshotTransform = { it.copy(revision = it.revision.copy(seq = it.revision.seq + 1)) }
                    "payload" -> store.delete(skillPayloadKey(record))
                    "read" -> events.beforeSnapshot = { error("readback unavailable") }
                }
                throw cancelled
            }
            val failure = assertFailsWith<CancellationException> { owner.commit(install(owner, skillOwnerFixture("a"))) }
            val causes = skillFailureCauses(failure)
            assertTrue(causes.any { it === cancelled })
            assertTrue(causes.any { it.suppressedExceptions.isNotEmpty() })
            assertTrue(owner.state.persistenceUnknown)
            assertTrue(owner.state.skills.isEmpty())
        }
    }

    @Test fun malformedEmptyRevisionCannotImportLegacySkillsOrWritePrivateInputs() = runTest {
        val transforms: List<(JournalRevision) -> JournalRevision> = listOf(
            { it.copy(stream = "foreign") }, { it.copy(seq = -1) }, { it.copy(resetEpoch = -1) },
        )
        for (transform in transforms) {
            val store = InMemoryKeyValueStore()
            val legacy = json.encodeToString(ListSerializer(Skill.serializer()), listOf(skillOwnerFixture("legacy")))
            store.write("skills", legacy)
            val events = SkillFaultJournal().apply { snapshotTransform = { it.copy(revision = transform(it.revision)) } }
            assertFails { SkillInputJournal(store, events, json).restore() }
            assertTrue(events.read(SkillInputJournal.STREAM).isEmpty())
            assertTrue(store.keys(SkillInputJournal.PREFIX).isEmpty())
            assertEquals(legacy, store.read("skills"))
        }
    }

    @Test fun highWaterMarkMustIdentifyTheCompleteOrderedPrefix() = runTest {
        for (delta in listOf(-1L, 1L)) {
            val store = InMemoryKeyValueStore()
            val events = SkillFaultJournal()
            val owner = SkillInputJournal(store, events, json)
            owner.restore()
            owner.commit(install(owner, skillOwnerFixture("a")))
            events.snapshotTransform = { it.copy(revision = it.revision.copy(seq = it.revision.seq + delta)) }
            assertFails { SkillInputJournal(store, events, json).restore() }
        }
    }

    @Test fun aNoOpCannotAcknowledgeAnotherOwnersNewerState() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val first = SkillInputJournal(store, events, json)
        first.restore()
        first.commit(install(first, skillOwnerFixture("a")))
        val oldRef = checkNotNull(first.state.ref("a"))
        val second = SkillInputJournal(store, events, json)
        second.restore()
        second.commit(SkillMachine.Intent.SetEnabled(checkNotNull(second.state.ref("a")), false, 2))
        assertFails { first.commit(SkillMachine.Intent.SetEnabled(oldRef, true, 3)) }
        assertTrue(first.state.persistenceUnknown)
        val reopened = SkillInputJournal(store, events, json)
        reopened.restore()
        assertFalse(reopened.state.skills.getValue("a").skill.enabled)
    }

    @Test fun malformedOrDuplicateLegacyDataIsPreservedWithoutAnEmptyInitialization() = runTest {
        val values = listOf("{broken", json.encodeToString(ListSerializer(Skill.serializer()),
            listOf(skillOwnerFixture("same"), skillOwnerFixture("same", name = "Another"))))
        for (legacy in values) {
            val store = InMemoryKeyValueStore().apply { write("skills", legacy) }
            val events = SkillFaultJournal()
            assertFails { SkillInputJournal(store, events, json).restore() }
            assertTrue(events.read(SkillInputJournal.STREAM).isEmpty())
            assertEquals(legacy, store.read("skills"))
        }
    }

    @Test fun aDroppedStreamOrNewResetEpochCannotRestoreTheLegacyCache() = runTest {
        for (reset in listOf(false, true)) {
            val store = InMemoryKeyValueStore().apply {
                write("skills", json.encodeToString(ListSerializer(Skill.serializer()), listOf(skillOwnerFixture("obsolete"))))
            }
            val events = SkillFaultJournal()
            if (reset) events.reset() else events.drop(SkillInputJournal.STREAM)
            val owner = SkillInputJournal(store, events, json)
            owner.restore()
            assertTrue(owner.state.initialized)
            assertTrue(owner.state.skills.isEmpty())
            val reopened = SkillInputJournal(store, events, json)
            reopened.restore()
            assertEquals(owner.state, reopened.state)
        }
    }

    private fun install(owner: SkillInputJournal, skill: Skill) =
        SkillMachine.Intent.Install(owner.state.installBasis(skill.nameKey), skill, 1)
}

internal val skillOwnerTestJson = Json { encodeDefaults = true }
internal fun skillOwnerFixture(id: String, name: String = "Skill $id", instructions: String = "Use the saved instruction.",
    tags: List<String> = emptyList()) = Skill(id, name, "When applicable", instructions, tags)

internal fun skillPayloadKey(record: JournalRecord): String = SkillInputJournal.PREFIX +
    skillOwnerTestJson.parseToJsonElement(record.detail).jsonObject.getValue("id").jsonPrimitive.content

internal fun skillFailureCauses(failure: Throwable): List<Throwable> = buildList {
    var current: Throwable? = failure
    while (true) {
        val next = current ?: break
        if (any { it === next }) break
        add(next)
        current = next.cause
    }
}

/** Real in-memory CAS history with faults only at acknowledgement/read boundaries. */
internal class SkillFaultJournal : EventJournal {
    private var delegate: EventJournal = InMemoryEventJournal()
    private var epoch = 0L
    var acknowledgement: (JournalRecord) -> JournalRecord = { it }
    var snapshotTransform: (JournalSnapshot) -> JournalSnapshot = { it }
    var beforeSnapshot: suspend () -> Unit = { }
    var afterAppend: suspend (JournalRecord) -> Unit = { }
    var appendFailure: Throwable? = null

    fun reset() { delegate = InMemoryEventJournal(); epoch++ }
    override suspend fun snapshot(stream: String): JournalSnapshot {
        beforeSnapshot()
        val current = delegate.snapshot(stream)
        return snapshotTransform(current.copy(revision = current.revision.copy(resetEpoch = epoch)))
    }
    override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
        if (expected.resetEpoch != epoch) return null
        val record = delegate.append(expected.copy(resetEpoch = 0), operation, at, detail) ?: return null
        afterAppend(record)
        appendFailure?.let { throw it }
        return acknowledgement(record)
    }
    override suspend fun append(stream: String, operation: String, at: Long, detail: String) = delegate.append(stream, operation, at, detail)
    override suspend fun streams() = delegate.streams()
    override suspend fun read(stream: String) = delegate.read(stream)
    override suspend fun drop(stream: String) = delegate.drop(stream)
    override suspend fun drop(expected: JournalRevision): Boolean =
        expected.resetEpoch == epoch && delegate.drop(expected.copy(resetEpoch = 0))
}
