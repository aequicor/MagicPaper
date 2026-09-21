package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.Serializable

/** An accounting capture fences late observations; it never authorizes a provider call. */
@Serializable sealed interface UsageObservation {
    @Serializable data class Captured(val generationId: String) : UsageObservation
    @Serializable data object Unavailable : UsageObservation
}

/** One authority for usage records, replacement identities, context snapshots and native counters. */
object UsageMachine : Machine<UsageMachine.State, UsageMachine.Input, UsageMachine.Effect> {
    override val id = MachineId("usage")
    override val space get() = UsageSpace
    /**
     * This owner has never emitted effects: [Transition] carries a refusal as a message and nothing
     * else. The contract wants a refusal to be a value the harness can recognise, so [step] shows it as
     * one; [Transition], [reduce] and every call site keep the message and stay as they are.
     */
    sealed interface Effect { data class Reject(val reason: String) : Effect }
    override fun step(state: State, input: Input) = reduce(state, input).let { transition ->
        Step(transition.state, listOfNotNull(transition.rejection?.let(Effect::Reject)))
    }

    @Serializable data class Stamp(val id: String, val at: Long)
    @Serializable data class CumulativeProof(val key: String, val fingerprint: String, val total: TokenUsage,
        val last: TokenUsage, val record: UsageRecord)
    @ConsistentCopyVisibility
    data class State internal constructor(
        val archive: UsageArchive = UsageArchive(startedAt = 0),
        val initialized: Boolean = false,
        val generationId: String = "",
        val retiredIds: Set<String> = emptySet(),
        val cumulative: Map<String, CumulativeProof> = emptyMap(),
        val persistenceUnknown: Boolean = false,
    )
    @Serializable sealed interface Input { val stamp: Stamp }
    @Serializable sealed interface Intent : Input {
        @Serializable data class Import(val archive: UsageArchive, override val stamp: Stamp) : Intent
        @Serializable data class Clear(override val stamp: Stamp) : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable data class Initialized(val archive: UsageArchive, override val stamp: Stamp) : Fact
        @Serializable data class Recorded(val observation: UsageObservation.Captured, val record: UsageRecord,
            val replacesId: String?, override val stamp: Stamp) : Fact
        @Serializable data class ContextObserved(val observation: UsageObservation.Captured,
            val snapshot: ContextUsageSnapshot, override val stamp: Stamp) : Fact
        @Serializable data class CumulativeObserved(val observation: UsageObservation.Captured,
            val value: CumulativeProof, override val stamp: Stamp) : Fact
        @Serializable data class PersistenceUnknown(override val stamp: Stamp) : Fact
    }
    data class Transition(val state: State, val rejection: String? = null)
    fun initial() = State()

    fun reduce(state: State, input: Input): Transition = try {
        require(input.stamp.id.isNotBlank() && input.stamp.at >= 0) { "Некорректная запись статистики" }
        if (input is Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        require(!state.persistenceUnknown) { "Сохранение статистики не подтверждено" }
        fun import(archive: UsageArchive): Transition {
            validateArchive(archive)
            return Transition(State(archive = archive.copy(records = archive.records.toList(),
                contexts = archive.contexts.toMap(), cursors = archive.cursors.toMap()),
                initialized = true, generationId = input.stamp.id))
        }
        when (input) {
            is Fact.Initialized -> {
                require(!state.initialized) { "Статистика уже загружена" }
                import(input.archive)
            }
            is Intent.Import -> import(input.archive)
            is Intent.Clear -> import(UsageArchive(startedAt = input.stamp.at))
            is Fact.Recorded -> {
                state.requireObservation(input.observation)
                validateRecord(input.record)
                val old = state.archive.records.singleOrNull { it.id == input.record.id }
                val replaced = input.replacesId?.takeIf { it != input.record.id }?.let { id ->
                    require(id.isNotBlank()) { "Не указан заменяемый расход" }
                    state.archive.records.singleOrNull { it.id == id }
                }
                require(input.record.id !in state.retiredIds) { "Эта запись расхода уже заменена" }
                old?.let {
                    require(sameIdentity(it, input.record)) { "Расход принадлежит другому запросу" }
                    require(knownUsageMatches(it, input.record)) { "Известный расход изменился" }
                }
                replaced?.let { require(!it.completed && sameIdentity(it, input.record)) { "Нельзя заменить чужой подтверждённый расход" } }
                if (input.replacesId != null && input.replacesId != input.record.id && replaced == null)
                    require(old != null && input.replacesId in state.retiredIds) { "Заменяемый расход не найден" }
                val saved = input.record.copy(createdAt = old?.createdAt ?: replaced?.createdAt ?: input.record.createdAt)
                require(old?.completed != true || saved == old) { "Подтверждённый расход изменился" }
                val removed = setOfNotNull(input.replacesId?.takeIf { it != input.record.id })
                val records = state.archive.records.filterNot { it.id in removed }.let { all ->
                    if (old == null) all + saved else all.map { if (it.id == saved.id) saved else it }
                }
                Transition(state.copy(archive = state.archive.copy(records = records), retiredIds = state.retiredIds + removed))
            }
            is Fact.ContextObserved -> {
                state.requireObservation(input.observation)
                val snapshot = input.snapshot
                validateContext(snapshot)
                val previous = state.archive.contexts[snapshot.conversationId]
                if (previous != null && previous.updatedAt > snapshot.updatedAt) Transition(state)
                else Transition(state.copy(archive = state.archive.copy(contexts = state.archive.contexts + (snapshot.conversationId to snapshot))))
            }
            is Fact.CumulativeObserved -> {
                state.requireObservation(input.observation)
                val value = input.value
                require(value.key.isNotBlank() && value.fingerprint.isNotBlank()) { "Не указана идентичность счётчика" }
                validateTokens(value.total); validateTokens(value.last); validateRecord(value.record)
                val id = "${value.key}:${value.fingerprint}" // Existing persisted usage IDs stay unchanged.
                val known = state.cumulative[id]
                if (known != null) {
                    require(known == value.copy(record = value.record.copy(id = known.record.id, createdAt = known.record.createdAt))) {
                        "Сохранённое показание счётчика изменилось"
                    }
                    Transition(state) // A late historical notification must not rewind the current cursor.
                } else {
                    val cursor = state.archive.cursors[value.key]
                    val existing = state.archive.records.singleOrNull { it.id == id }
                    val proof = value.copy(record = value.record.copy(id = id, createdAt = existing?.createdAt ?: value.record.createdAt))
                    if (existing != null) {
                        // Legacy archives lack historical total proofs. Only the exact current cursor plus
                        // its recorded last usage establishes a first journal proof; older ambiguity rejects.
                        require(cursor?.fingerprint == value.fingerprint && cursor.tokens == value.total &&
                            existing == proof.record.copy(tokens = value.last)) { "Предыдущее показание счётчика не подтверждено" }
                        Transition(state.copy(cumulative = state.cumulative + (id to proof)))
                    } else {
                        require(id !in state.retiredIds && cursor?.fingerprint != value.fingerprint) { "Идентичность счётчика уже использована" }
                        val delta = if (cursor == null || (value.total.totalTokens ?: 0) < (cursor.tokens.totalTokens ?: 0)) value.last
                            else value.total.delta(cursor.tokens)
                        val record = proof.record.copy(tokens = delta)
                        Transition(state.copy(archive = state.archive.copy(records = state.archive.records + record,
                            cursors = state.archive.cursors + (value.key to UsageCursor(value.total, value.fingerprint))),
                            cumulative = state.cumulative + (id to proof)))
                    }
                }
            }
            is Fact.PersistenceUnknown -> error("Handled above")
        }
    } catch (failure: IllegalArgumentException) {
        Transition(state, failure.message ?: "Статистика не изменена")
    }

    private fun State.requireObservation(value: UsageObservation.Captured) {
        require(initialized && value.generationId.isNotBlank() && value.generationId == generationId) { "Запись относится к прежней статистике" }
    }
    private fun knownUsageMatches(a: UsageRecord, b: UsageRecord): Boolean {
        val before = a.tokens
        val after = b.tokens
        return listOf(before.input to after.input, before.output to after.output,
            before.cacheRead to after.cacheRead, before.cacheWrite to after.cacheWrite,
            before.reasoning to after.reasoning, before.cachedOutput to after.cachedOutput,
            before.total to after.total).all { (old, next) -> old == null || old == next } &&
            (a.cost == null || a.cost == b.cost) &&
            (a.generatedImages == null || a.generatedImages == b.generatedImages) &&
            (a.generatedVideoSeconds == null || a.generatedVideoSeconds == b.generatedVideoSeconds)
    }
    private fun sameIdentity(a: UsageRecord, b: UsageRecord) = a.scope == b.scope && a.kind == b.kind &&
        a.provider == b.provider && a.model == b.model && a.subscription == b.subscription
    private fun validateArchive(archive: UsageArchive) {
        require(archive.startedAt >= 0 && archive.records.map { it.id }.distinct().size == archive.records.size) { "Архив содержит повторные расходы" }
        archive.records.forEach(::validateRecord)
        archive.contexts.forEach { (key, value) -> require(key == value.conversationId); validateContext(value) }
        archive.cursors.forEach { (key, value) -> require(key.isNotBlank() && value.fingerprint.isNotBlank()); validateTokens(value.tokens) }
    }
    private fun validateRecord(record: UsageRecord) {
        require(record.id.isNotBlank() && record.createdAt >= 0 && record.requests >= 0 && record.pages >= 0 && record.contentRequests >= 0)
        validateTokens(record.tokens)
        record.cost?.let { require(it.amount.isFinite() && it.amount >= 0 && it.currency.isNotBlank()) }
        require(record.generatedImages?.let { it >= 0 } != false)
        require(record.generatedVideoSeconds?.let { it.isFinite() && it >= 0 } != false)
    }
    private fun validateTokens(value: TokenUsage) = require(listOf(value.input, value.output, value.cacheRead,
        value.cacheWrite, value.reasoning, value.cachedOutput, value.total).all { it == null || it >= 0 }) { "Некорректное число токенов" }
    private fun validateContext(value: ContextUsageSnapshot) {
        require(value.conversationId.isNotBlank() && value.updatedAt >= 0 && value.used?.let { it >= 0 } != false &&
            value.limit?.let { it > 0 } != false) { "Некорректный размер контекста" }
    }
}
