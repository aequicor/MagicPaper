package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.*

class SkillCatalogOwnerTest {
    private val json = skillOwnerTestJson

    @Test fun relevantSkillsObserveAnotherOwnersDisableAndDeleteWithoutManualReload() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = SkillStore(store, events, json, dispatcher)
        val second = SkillStore(store, events, json, dispatcher)
        first.start()
        install(first, skillOwnerFixture("a"))
        second.start()
        second.setEnabled(second.catalog.value.items.single().ref, false)
        assertFalse(first.relevantFor("request", 8).single().enabled)
        assertFalse(first.catalog.value.items.single().skill.enabled)
        assertEquals(second.catalog.value.items.single().ref, first.catalog.value.items.single().ref)
        second.delete(second.catalog.value.items.single().ref)
        assertTrue(first.all().isEmpty())
        assertTrue(first.catalog.value.items.isEmpty())
        assertFalse(first.catalog.value.unknown)
    }

    @Test fun anotherOwnersResetInvalidatesCachedInstructionsAndOldUiReferences() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = SkillStore(store, events, json, dispatcher)
        first.start()
        install(first, skillOwnerFixture("obsolete"))
        val oldRef = first.catalog.value.items.single().ref
        val oldBasis = checkNotNull(first.catalog.value.installBasis("Skill obsolete"))
        events.reset()
        val second = SkillStore(store, events, json, dispatcher)
        second.start()
        assertTrue(second.all().isEmpty(), "The old compatible cache cannot repopulate a new journal epoch")
        assertTrue(first.relevantFor("request", 8).isEmpty())
        assertNotEquals(oldRef.generation, first.catalog.value.revision.generation)
        assertFailsWith<SkillCommandRejected> { first.setEnabled(oldRef, true) }
        assertIs<SkillInstallOutcome.Conflict>(first.install(skillOwnerFixture("replacement", name = "Skill obsolete"), oldBasis))
        assertTrue(first.all().isEmpty())
    }

    @Test fun staleNameAndEntryReferencesCannotOverwriteAReplacement() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        owner.start()
        val emptyBasis = checkNotNull(owner.catalog.value.installBasis("Same"))
        assertIs<SkillInstallOutcome.Installed>(owner.install(skillOwnerFixture("first", name = "Same"), emptyBasis))
        val original = owner.catalog.value.items.single()
        val originalBasis = checkNotNull(owner.catalog.value.installBasis("Same"))
        owner.delete(original.ref)
        install(owner, skillOwnerFixture("second", name = "Same"))
        val records = events.read(SkillInputJournal.STREAM)
        assertIs<SkillInstallOutcome.Conflict>(owner.install(skillOwnerFixture("stale", name = "Same"), originalBasis))
        assertIs<SkillInstallOutcome.Conflict>(owner.install(skillOwnerFixture("stale-empty", name = "Same"), emptyBasis))
        assertFailsWith<SkillCommandRejected> { owner.delete(original.ref) }
        assertFailsWith<SkillCommandRejected> { owner.setEnabled(original.ref, false) }
        assertEquals("second", owner.all().single().id)
        assertEquals(records, events.read(SkillInputJournal.STREAM), "Rejected stale requests cannot append replacement facts")
    }

    @Test fun committedCacheFailureKeepsTheConfirmedCatalogAndAnActionableFailure() = runTest {
        val memory = InMemoryKeyValueStore()
        var failCache = false
        val store = object : KeyValueStore by memory {
            override fun write(key: String, value: String) {
                if (key == "skills" && failCache) error("cache unavailable")
                memory.write(key, value)
            }
        }
        val events = SkillFaultJournal()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SkillStore(store, events, json, dispatcher)
        owner.start()
        failCache = true
        val failure = assertFailsWith<StorageException> { install(owner, skillOwnerFixture("committed")) }
        assertTrue(failure.committed)
        assertFalse(owner.catalog.value.unknown)
        assertNotNull(owner.catalog.value.failure)
        assertEquals("committed", owner.catalog.value.items.single().skill.id)
        val records = events.read(SkillInputJournal.STREAM)
        failCache = false
        val reopened = SkillStore(store, events, json, dispatcher)
        reopened.start()
        assertEquals("committed", reopened.all().single().id)
        assertEquals(records, events.read(SkillInputJournal.STREAM))
    }

    @Test fun initialCheckpointFailureIsKnownAndReloadDoesNotAppendAnotherInitialization() = runTest {
        val memory = InMemoryKeyValueStore()
        var failCache = true
        val store = object : KeyValueStore by memory {
            override fun write(key: String, value: String) {
                if (key == "skills" && failCache) error("cache unavailable")
                memory.write(key, value)
            }
        }
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        assertTrue(assertFailsWith<StorageException> { owner.start() }.committed)
        assertTrue(owner.catalog.value.initialized)
        assertFalse(owner.catalog.value.unknown)
        assertNotNull(owner.catalog.value.failure)
        val records = events.read(SkillInputJournal.STREAM)
        assertEquals(1, records.size)
        failCache = false
        owner.reload()
        assertNull(owner.catalog.value.failure)
        assertEquals(records, events.read(SkillInputJournal.STREAM))
    }

    @Test fun finishResetReleasesAdmissionAfterACacheOnlyFailureAndExplicitReloadRecovers() = runTest {
        val memory = InMemoryKeyValueStore()
        var failCache = false
        val store = object : KeyValueStore by memory {
            override fun write(key: String, value: String) {
                if (key == "skills" && failCache) error("cache unavailable")
                memory.write(key, value)
            }
        }
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        owner.start()
        install(owner, skillOwnerFixture("old"))
        val oldRef = owner.catalog.value.items.single().ref
        owner.prepareForReset()
        events.reset()
        failCache = true
        assertTrue(assertFailsWith<StorageException> { owner.finishReset() }.committed)
        assertFalse(owner.catalog.value.resetting)
        assertFalse(owner.catalog.value.unknown)
        assertTrue(owner.catalog.value.items.isEmpty())
        assertNotNull(owner.catalog.value.failure)
        failCache = false
        owner.reload()
        assertFalse(owner.catalog.value.resetting)
        assertNull(owner.catalog.value.failure)
        assertFailsWith<SkillCommandRejected> { owner.delete(oldRef) }
        install(owner, skillOwnerFixture("fresh"))
        assertEquals("fresh", owner.all().single().id)
    }

    @Test fun finishResetRetainsRestoreUnknownButDoesNotPermanentlyBlockReload() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        owner.start()
        install(owner, skillOwnerFixture("old"))
        owner.prepareForReset()
        events.reset()
        events.beforeSnapshot = { error("journal unavailable") }
        assertFails { owner.finishReset() }
        assertFalse(owner.catalog.value.resetting)
        assertTrue(owner.catalog.value.unknown)
        assertNotNull(owner.catalog.value.failure)
        events.beforeSnapshot = { }
        owner.reload()
        assertFalse(owner.catalog.value.unknown)
        assertTrue(owner.all().isEmpty())
    }

    @Test fun malformedLegacyCannotBecomeAConfirmedEmptyCatalog() = runTest {
        val store = InMemoryKeyValueStore().apply { write("skills", "{broken private cache") }
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        assertFails { owner.start() }
        assertTrue(owner.catalog.value.unknown)
        assertNotNull(owner.catalog.value.failure)
        assertFalse(owner.catalog.value.initialized)
        assertTrue(events.read(SkillInputJournal.STREAM).isEmpty())
        assertEquals("{broken private cache", store.read("skills"))
        store.write("skills", json.encodeToString(ListSerializer(Skill.serializer()), listOf(skillOwnerFixture("recovered"))))
        owner.reload()
        assertEquals("recovered", owner.all().single().id)
        assertFalse(owner.catalog.value.unknown)
    }

    @Test fun changedPrivateHistoryMakesALibraryReadUnknownInsteadOfReturningCachedInstructions() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        owner.start()
        install(owner, skillOwnerFixture("a", instructions = "private instruction"))
        val key = skillPayloadKey(events.read(SkillInputJournal.STREAM).last())
        store.write(key, checkNotNull(store.read(key)).replace("private instruction", "changed instruction"))
        assertFails { owner.relevantFor("request", 8) }
        assertTrue(owner.catalog.value.unknown)
        assertNotNull(owner.catalog.value.failure)
        assertTrue(checkNotNull(store.read(key)).contains("changed instruction"))
    }

    @Test fun installFreezesMutableValuesBeforeWaitingForTheOwnerMutex() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        owner.start()
        val basis = checkNotNull(owner.catalog.value.installBasis("Queued"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = true
        events.beforeSnapshot = {
            if (hold) { hold = false; entered.complete(Unit); release.await() }
        }
        val holder = async { owner.reload() }
        entered.await()
        val tags = mutableListOf("captured", "second")
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            owner.install(skillOwnerFixture("queued", name = "Queued", tags = tags), basis)
        }
        tags[0] = "late mutation"
        tags += "not admitted"
        release.complete(Unit)
        holder.await()
        val result = assertIs<SkillInstallOutcome.Installed>(queued.await())
        assertEquals(listOf("captured", "second"), result.skill.tags)
        assertEquals(listOf("captured", "second"), owner.all().single().tags)
        val reopened = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        reopened.start()
        assertEquals(listOf("captured", "second"), reopened.all().single().tags)
    }

    @Test fun importFreezesTheListAndNestedTagsBeforeWaitingForTheOwnerMutex() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        owner.start()
        val revision = owner.catalog.value.revision
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = true
        events.beforeSnapshot = {
            if (hold) { hold = false; entered.complete(Unit); release.await() }
        }
        val holder = async { owner.reload() }
        entered.await()
        val tags = mutableListOf("captured", "second")
        val values = mutableListOf(skillOwnerFixture("captured", tags = tags))
        val queued = async(start = CoroutineStart.UNDISPATCHED) { owner.importSkills(values, revision) }
        tags.clear()
        values += skillOwnerFixture("not-admitted")
        release.complete(Unit)
        holder.await()
        queued.await()
        assertEquals(listOf("captured"), owner.all().map { it.id })
        assertEquals(listOf("captured", "second"), owner.all().single().tags)
    }

    @Test fun publishedAndReturnedSkillValuesDoNotAliasThePrivateOwnerState() = runTest {
        val store = InMemoryKeyValueStore()
        val events = SkillFaultJournal()
        val owner = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        owner.start()
        val result = install(owner, skillOwnerFixture("a", tags = mutableListOf("one", "two")))
        @Suppress("UNCHECKED_CAST")
        (result.skill.tags as MutableList<String>)[0] = "changed returned value"
        @Suppress("UNCHECKED_CAST")
        (owner.catalog.value.items.single().skill.tags as MutableList<String>)[1] = "changed published value"
        assertEquals(listOf("one", "two"), owner.all().single().tags)
        val reopened = SkillStore(store, events, json, StandardTestDispatcher(testScheduler))
        reopened.start()
        assertEquals(listOf("one", "two"), reopened.all().single().tags)
    }

    private suspend fun install(owner: SkillStore, skill: Skill): SkillInstallOutcome.Installed =
        assertIs<SkillInstallOutcome.Installed>(owner.install(skill, checkNotNull(owner.catalog.value.installBasis(skill.name))))
}
