package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Application-owned catalog. Legacy storage is a compatible cache, never a second writer. */
class SkillStore(
    private val store: KeyValueStore,
    events: EventJournal,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SkillRepository, SkillLibrary, SkillCommands {
    private val lock = Mutex()
    private val journal = SkillInputJournal(store, events, json)
    private val mutableCatalog = MutableStateFlow(SkillCatalogSnapshot())
    override val catalog = mutableCatalog.asStateFlow()
    private var loaded = false
    private var resetting = false

    override suspend fun start() = owned { ensureLoaded() }
    override suspend fun reload() = owned { loaded = false; ensureLoaded() }
    override suspend fun all(): List<Skill> = owned {
        // Agent input must reflect disable/delete/reset performed by another application window.
        try { if (loaded && !journal.isCurrent()) loaded = false }
        catch (failure: Throwable) { journal.markUnknown(); failed("read", failure); throw failure }
        ensureLoaded()
        values()
    }
    override suspend fun relevantFor(query: String, limit: Int): List<Skill> = all()

    private suspend fun <T> owned(block: suspend () -> T): T = withContext(dispatcher) {
        lock.withLock {
            check(!resetting) { "Библиотека навыков очищается" }
            block()
        }
    }
    private fun freeze(skill: Skill) = skill.copy(tags = skill.tags.toList())
    private fun values() = journal.state.skills.values.map { freeze(it.skill) }.sortedBy { it.name }
    private fun publish(failure: String? = null) {
        val state = journal.state
        mutableCatalog.value = SkillCatalogSnapshot(state.initialized,
            SkillCatalogRevision(state.generation, state.catalogRevision),
            state.skills.values.sortedBy { it.skill.name }.map { SkillCatalogItem(checkNotNull(state.ref(it.skill.id)), freeze(it.skill)) },
            state.nameVersions.toMap(), state.persistenceUnknown, resetting, failure)
    }
    private fun failed(operation: String, failure: Throwable) {
        AppLog.error("skills.catalog", "operation_failed", mapOf("operation" to operation,
            "causeType" to failure::class.simpleName.orEmpty()))
        publish("Не удалось сохранить или загрузить навыки. Повторите действие.")
    }
    private fun checkpoint() {
        try { store.write("skills", json.encodeToString(ListSerializer(Skill.serializer()), values())) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            throw StorageException("checkpoint skills", StorageException.Kind.WRITE, failure, committed = true)
        }
    }
    private suspend fun ensureLoaded() {
        if (loaded) {
            check(!journal.state.persistenceUnknown) { "Требуется повторная загрузка библиотеки навыков" }
            return
        }
        try {
            journal.restore()
            loaded = true
        } catch (failure: Throwable) {
            journal.markUnknown()
            failed("restore", failure)
            throw failure
        }
        publish()
        // The journal is authoritative even if this compatible cache cannot be updated.
        try { checkpoint() }
        catch (failure: Throwable) { failed("checkpoint", failure); throw failure }
    }
    private suspend fun prepareCommand() {
        if (journal.state.persistenceUnknown) loaded = false
        ensureLoaded()
    }
    private suspend fun commit(input: SkillMachine.Intent) {
        val operation = input::class.simpleName.orEmpty()
        AppLog.info("skills.catalog", "command_started", mapOf("operation" to operation,
            "generation" to journal.state.generation, "revision" to journal.state.catalogRevision.toString()))
        try {
            journal.commit(input)
            publish()
            AppLog.info("skills.catalog", "command_committed", mapOf("operation" to operation,
                "generation" to journal.state.generation, "revision" to journal.state.catalogRevision.toString()))
            checkpoint()
        } catch (failure: SkillCommandRejected) {
            AppLog.debug("skills.catalog", "command_rejected", mapOf("operation" to operation))
            throw failure
        }
        catch (failure: Throwable) { failed("commit", failure); throw failure }
    }

    override suspend fun install(skill: Skill, expected: SkillInstallBasis): SkillInstallOutcome {
        val requested = freeze(skill)
        return owned {
            prepareCommand()
            val previous = journal.state.skills.values.singleOrNull { it.skill.nameKey == requested.nameKey }
            try { commit(SkillMachine.Intent.Install(expected, requested, Id.now())) }
            catch (rejected: SkillCommandRejected) { return@owned SkillInstallOutcome.Conflict(rejected.message.orEmpty()) }
            val installed = journal.state.skills.values.single { it.skill.nameKey == requested.nameKey }.skill
            SkillInstallOutcome.Installed(freeze(installed), previous != null)
        }
    }
    override suspend fun setEnabled(expected: SkillRef, enabled: Boolean) = owned {
        prepareCommand()
        commit(SkillMachine.Intent.SetEnabled(expected, enabled, Id.now()))
    }
    override suspend fun delete(expected: SkillRef) = owned {
        prepareCommand()
        commit(SkillMachine.Intent.Delete(expected))
    }
    override suspend fun importSkills(skills: List<Skill>, expected: SkillCatalogRevision) {
        val requested = skills.map(::freeze)
        owned {
            prepareCommand()
            commit(SkillMachine.Intent.Import(expected.generation, expected.revision, requested, Id.new()))
        }
    }
    override suspend fun clearSkills(expected: SkillCatalogRevision) = owned {
        prepareCommand()
        commit(SkillMachine.Intent.Clear(expected.generation, expected.revision, Id.new()))
    }

    /** The lock drains accepted writes; queued commands observe the closed admission before touching storage. */
    override suspend fun prepareForReset() = withContext(dispatcher) {
        lock.withLock { resetting = true; publish() }
    }
    override suspend fun finishReset() = withContext(dispatcher) {
        lock.withLock {
            // Restore the durable reset epoch. Previously captured UI refs cannot authorize new writes.
            loaded = false
            try { ensureLoaded() }
            finally {
                resetting = false
                publish(mutableCatalog.value.failure)
            }
        }
    }
}
