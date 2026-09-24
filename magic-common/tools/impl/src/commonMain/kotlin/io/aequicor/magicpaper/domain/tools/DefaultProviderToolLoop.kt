package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.data.storage.JournalSnapshot
import io.aequicor.magicpaper.data.storage.MachineTransitionLog
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A durable workflow around the same tool executor used by native transports. No HTTP/tool retry here. */
class DefaultProviderToolLoop(private val gateway: LlmGateway, private val journal: EventJournal,
    private val outputs: ProviderToolOutputs) : ProviderToolLoop {
    private val active = MutableStateFlow<Set<String>>(emptySet())
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun inspect(runId: String): ProviderToolRecovery = try {
        val (state, output) = inspectState(runId)
        when (state.phase) {
            ProviderToolMachine.Phase.NEW -> ProviderToolRecovery.Missing
            ProviderToolMachine.Phase.SUCCEEDED -> output?.let(ProviderToolRecovery::Completed) ?: ProviderToolRecovery.Unknown
            ProviderToolMachine.Phase.UNKNOWN -> ProviderToolRecovery.Unknown
            else -> ProviderToolRecovery.Interrupted
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        AppLog.error("provider_tools", "recovery_read_failed", mapOf("runId" to runId, "cause" to failure::class.simpleName.orEmpty()))
        ProviderToolRecovery.Unknown
    }

    override suspend fun restore(runId: String): ProviderToolMachine.State = inspectState(runId).first

    private suspend fun inspectState(runId: String): Pair<ProviderToolMachine.State, ProviderToolOutput?> {
        val snapshot = journal.snapshot(stream(runId))
        var state = replay(snapshot, runId)
        val output = if (state.phase in outputPhases) verifiedOutput(state.output) else null
        if (state.phase in outputPhases) {
            state = if (output == null) ProviderToolMachine.reduce(state, ProviderToolMachine.Fact.PersistenceUnknown).state
                else if (state.phase == ProviderToolMachine.Phase.OUTPUT_WRITING)
                    ProviderToolMachine.reduce(state, ProviderToolMachine.Fact.OutputStored(output.ref)).state else state
        }
        return ProviderToolMachine.reduce(state, ProviderToolMachine.Fact.Restored).state to output
    }

    override suspend fun run(runId: String, profile: LlmProfile, messages: List<LlmMessage>, tools: ToolSession,
        maxTurns: Int, maxCalls: Int): String {
        active.update { check(runId !in it) { "Этот запрос уже выполняется" }; it + runId }
        try {
            val snapshot = journal.snapshot(stream(runId))
            val durable = RunJournal(runId, replay(snapshot, runId), snapshot.revision)
            val definitions = tools.definitions.map { LlmToolDefinition(it.wireName, it.description, it.schema) }
            val identity = toolArgumentsFingerprint(buildJsonObject {
                put("profile", profile.id); put("provider", profile.provider.name); put("model", profile.modelId)
                put("messages", json.encodeToJsonElement(messages)); put("tools", json.encodeToJsonElement(definitions))
                put("projectId", tools.context.projectId); put("ownerSessionId", tools.context.ownerSessionId)
                put("sessionId", tools.context.sessionId); put("requestId", tools.context.requestId)
                put("generation", tools.context.runtimeGeneration); put("role", tools.context.role.name); put("mode", tools.context.mode.name)
            })
            if (snapshot.records.isNotEmpty()) {
                if (durable.state.phase in outputPhases) {
                    val output = verifiedOutput(durable.state.output)
                    if (output != null && durable.state.identity == identity) {
                        if (durable.state.phase == ProviderToolMachine.Phase.OUTPUT_WRITING)
                            durable.commit(ProviderToolMachine.Fact.OutputStored(output.ref))
                        return output.text
                    }
                    durable.commit(ProviderToolMachine.Fact.PersistenceUnknown)
                    throw ProviderToolRunFailure("Сохранённый ответ не удалось подтвердить. Проверьте историю перед новым запросом.")
                }
                durable.commit(ProviderToolMachine.Fact.Restored)
                throw ProviderToolRunFailure("Этот запрос уже выполнялся. Проверьте сохранённый результат и отправьте новое сообщение.")
            }
            durable.commit(ProviderToolMachine.Intent.Start(runId, identity, definitions.map { it.name }.toSet(), maxTurns, maxCalls))
            val exchanges = mutableListOf<LlmToolExchange>()
            try {
                while (true) {
                    val attempt = Id.new()
                    val modelEffect = durable.commit(ProviderToolMachine.Intent.RequestModel(attempt)).single() as ProviderToolMachine.Effect.InvokeModel
                    val turn = try { gateway.turn(profile, messages, definitions, exchanges) }
                    catch (failure: Exception) {
                        durable.failure("provider", providerOutcomeUnknown(failure))
                        throw failure
                    }
                    val calls = turn.calls.map { ProviderToolMachine.Call(it.id, it.name, toolArgumentsFingerprint(it.arguments)) }
                    if (turn.calls.isEmpty()) {
                        val output = ProviderToolOutput(ProviderToolMachine.OutputRef(runId, modelEffect.attempt, identity,
                            providerOutputDigest(runId, modelEffect.attempt, identity, turn.text)), turn.text)
                        withContext(NonCancellable) {
                            durable.commit(ProviderToolMachine.Fact.ModelReturned(modelEffect.attempt, calls, turn.text.isNotBlank(), output.ref))
                            val effect = durable.commit(ProviderToolMachine.Intent.StoreOutput).single() as ProviderToolMachine.Effect.PersistOutput
                            check(effect.output == output.ref)
                            var writeFailure: Exception? = null
                            try { outputs.save(output) } catch (failure: Exception) { writeFailure = failure }
                            if (verifiedOutput(output.ref) != output) throw writeFailure ?: ProviderToolRunFailure("Не удалось подтвердить сохранение ответа")
                            durable.commit(ProviderToolMachine.Fact.OutputStored(output.ref))
                            if (writeFailure is CancellationException) throw writeFailure
                        }
                        currentCoroutineContext().ensureActive()
                        return turn.text
                    }
                    durable.commit(ProviderToolMachine.Fact.ModelReturned(modelEffect.attempt, calls, turn.text.isNotBlank()))
                    val results = mutableListOf<LlmToolResult>()
                    for (call in turn.calls) {
                        val toolEffect = durable.commit(ProviderToolMachine.Intent.ExecuteTool(call.id)).single() as ProviderToolMachine.Effect.InvokeTool
                        try {
                            val output = tools.call(toolEffect.id, call.name, call.arguments)
                            durable.commit(ProviderToolMachine.Fact.ToolReturned(call.id, ToolPhase.SUCCEEDED))
                            results += LlmToolResult(call.id, call.name, output)
                        } catch (cancelled: CancellationException) {
                            try { withContext(NonCancellable) {
                                val outcome = observedOutcome(tools, call.id)
                                if (outcome != null) durable.recordToolOutcome(call.id, outcome)
                            } } catch (failure: Exception) {
                                cancelled.addSuppressed(failure)
                                AppLog.error("provider_tools", "cancelled_tool_outcome_unknown",
                                    diagnosticFields(failure, runId))
                            }
                            throw cancelled
                        } catch (failure: Exception) {
                            val outcome = observedOutcome(tools, call.id) ?: if (failure is RejectedToolCall) ToolPhase.FAILED else null
                            durable.recordToolOutcome(call.id, outcome ?: ToolPhase.UNKNOWN)
                            if (outcome !in setOf(ToolPhase.FAILED, ToolPhase.CANCELLED)) throw failure
                            results += LlmToolResult(call.id, call.name, buildJsonObject {
                                put("error", if (failure is ToolArgumentRejection) failure.message.orEmpty()
                                    else "Инструмент не выполнен. Проверьте аргументы и используйте новый идентификатор для исправленного вызова.")
                            }, isError = true)
                        }
                    }
                    exchanges += LlmToolExchange(turn, results)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { durable.cancel() }
                throw cancelled
            } catch (failure: Exception) {
                // Leave the durable output intent intact: an exact immutable artifact can prove this
                // handoff after restart. An unrelated UNKNOWN tool never has such an output reference.
                if (durable.state.phase in setOf(ProviderToolMachine.Phase.OUTPUT_PENDING, ProviderToolMachine.Phase.OUTPUT_WRITING)) durable.outputUnknown()
                else durable.failure("execution", durable.state.phase in setOf(ProviderToolMachine.Phase.MODEL_PENDING, ProviderToolMachine.Phase.TOOL_PENDING))
                AppLog.error("provider_tools", "run_failed", diagnosticFields(failure, runId) +
                    mapOf("phase" to durable.state.phase.name,
                        "outcome" to if (durable.state.phase == ProviderToolMachine.Phase.UNKNOWN) "unknown" else "failed"))
                throw ProviderToolRunFailure(if (durable.state.phase == ProviderToolMachine.Phase.UNKNOWN)
                    "Исход предыдущего действия не подтверждён. Проверьте результат перед новым запросом."
                    else "Не удалось завершить запрос с инструментами. Проверьте подключение и параметры запроса.", failure)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ProviderToolRunFailure) { throw failure }
        catch (failure: Exception) {
            AppLog.error("provider_tools", "request_start_failed", diagnosticFields(failure, runId))
            throw ProviderToolRunFailure("Не удалось прочитать или сохранить состояние запроса. Проверьте доступность хранилища и повторите попытку.", failure)
        } finally { active.update { it - runId } }
    }

    private suspend fun verifiedOutput(ref: ProviderToolMachine.OutputRef?): ProviderToolOutput? {
        if (ref == null) return null
        return try {
            outputs.get(ref.runId, ref.attempt)?.takeIf { it.ref == ref &&
                providerOutputDigest(ref.runId, ref.attempt, ref.identity, it.text) == ref.digest }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("provider_tools", "output_read_failed", diagnosticFields(failure, ref.runId))
            null
        }
    }

    private suspend fun observedOutcome(tools: ToolSession, id: String): ToolPhase? = try {
        tools.receipt(id)?.phase?.takeIf { it in setOf(ToolPhase.SUCCEEDED, ToolPhase.FAILED, ToolPhase.CANCELLED, ToolPhase.UNKNOWN) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        AppLog.error("provider_tools", "receipt_read_failed", diagnosticFields(failure))
        null
    }

    @Serializable private data class Entry(val id: String, val input: ProviderToolMachine.Input, val resetEpoch: Long = 0)
    private fun stream(runId: String): String {
        require(runId.isNotBlank())
        return "provider-tools:" + runId.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun replay(snapshot: JournalSnapshot, runId: String): ProviderToolMachine.State {
        check(snapshot.revision.stream == stream(runId) && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) {
            "Журнал принадлежит другому запросу"
        }
        if (snapshot.records.isNotEmpty()) check(snapshot.records.last().seq == snapshot.revision.seq) { "Повреждена ревизия журнала инструментов" }
        var state = ProviderToolMachine.initial()
        var sequence = 0L
        val entries = mutableSetOf<String>()
        snapshot.records.forEach { record ->
            check(record.stream == snapshot.revision.stream && record.seq > sequence && record.seq <= snapshot.revision.seq) {
                "Повреждена последовательность журнала инструментов"
            }
            sequence = record.seq
            check(record.operation == OPERATION) { "Неизвестная запись журнала инструментов" }
            val entry = json.decodeFromString<Entry>(record.detail)
            check(entry.resetEpoch == snapshot.revision.resetEpoch && entry.id.isNotBlank() && entries.add(entry.id)) {
                "Запись принадлежит другому поколению журнала"
            }
            if (state.phase == ProviderToolMachine.Phase.NEW) check(entry.input is ProviderToolMachine.Intent.Start) { "Отсутствует начало запроса" }
            val before = state
            val next = ProviderToolMachine.reduce(before, entry.input)
            check(next.effects.none { it is ProviderToolMachine.Effect.Reject }) { "Повреждён журнал инструментов" }
            check(next.state.runId == runId) { "Журнал принадлежит другому запросу" }
            MachineTransitionLog.replay(ProviderToolMachine.id, ProviderToolMachine.space, before, entry.input, next.state, next.effects)
            state = next.state
        }
        return state
    }

    /** Persisted inputs contain identities/fingerprints only, never prompts, answers or continuation payloads. */
    private inner class RunJournal(private val runId: String, var state: ProviderToolMachine.State, private var revision: JournalRevision) {
        fun outputUnknown() = markUnknown()
        private fun markUnknown() {
            val before = state
            val next = ProviderToolMachine.reduce(before, ProviderToolMachine.Fact.PersistenceUnknown)
            MachineTransitionLog.append(ProviderToolMachine.id, ProviderToolMachine.space, before, ProviderToolMachine.Fact.PersistenceUnknown, next.state, next.effects)
            state = next.state
        }
        suspend fun commit(input: ProviderToolMachine.Input): List<ProviderToolMachine.Effect> {
            val before = state
            val next = ProviderToolMachine.reduce(before, input)
            next.effects.filterIsInstance<ProviderToolMachine.Effect.Reject>().firstOrNull()?.let {
                MachineTransitionLog.append(ProviderToolMachine.id, ProviderToolMachine.space, before, input, next.state, next.effects)
                error(it.reason)
            }
            val detail = json.encodeToString(Entry.serializer(), Entry(Id.new(), input, revision.resetEpoch))
            try {
                val record = journal.append(revision, OPERATION, Id.now(), detail)
                checkNotNull(record) { "Журнал изменён другим владельцем" }
                check(record.stream == revision.stream && record.seq > revision.seq && record.operation == OPERATION && record.detail == detail) {
                    "Подтверждение записи принадлежит другому журналу"
                }
                revision = revision.copy(seq = record.seq)
            } catch (failure: Exception) {
                val observed = try { withContext(NonCancellable) { journal.snapshot(revision.stream) } }
                catch (readFailure: Exception) {
                    markUnknown()
                    failure.addSuppressed(readFailure)
                    throw failure
                }
                try { replay(observed, runId) }
                catch (invalid: Exception) {
                    markUnknown()
                    failure.addSuppressed(invalid)
                    throw failure
                }
                val committed = observed.records.lastOrNull()?.takeIf { it.operation == OPERATION && it.detail == detail &&
                    it.stream == revision.stream && it.seq > revision.seq }
                if (committed == null || observed.revision.resetEpoch != revision.resetEpoch) {
                    if (observed.revision != revision) markUnknown()
                    throw failure
                }
                revision = observed.revision
                if (failure is CancellationException) {
                    MachineTransitionLog.append(ProviderToolMachine.id, ProviderToolMachine.space, before, input, next.state, next.effects)
                    state = next.state; throw failure
                }
            }
            MachineTransitionLog.append(ProviderToolMachine.id, ProviderToolMachine.space, before, input, next.state, next.effects)
            state = next.state
            return next.effects
        }
        suspend fun recordToolOutcome(id: String, phase: ToolPhase) {
            if (state.phase == ProviderToolMachine.Phase.TOOL_PENDING) commit(ProviderToolMachine.Fact.ToolReturned(id, phase))
        }
        suspend fun failure(operation: String, unknown: Boolean) {
            if (state.phase in terminal) return
            try { withContext(NonCancellable) { commit(ProviderToolMachine.Fact.Failed(operation, unknown)) } }
            catch (failure: Exception) {
                markUnknown()
                AppLog.error("provider_tools", "failure_record_unknown", diagnosticFields(failure, runId) +
                    mapOf("operation" to operation))
            }
        }
        suspend fun cancel() {
            if (state.phase in terminal) return
            try { commit(ProviderToolMachine.Intent.Cancel) }
            catch (failure: Exception) {
                markUnknown()
                AppLog.error("provider_tools", "cancellation_record_unknown", diagnosticFields(failure, runId))
            }
        }
    }
    private companion object {
        const val OPERATION = "provider-tools.input.v1"
        val outputPhases = setOf(ProviderToolMachine.Phase.OUTPUT_WRITING, ProviderToolMachine.Phase.SUCCEEDED)
        val terminal = setOf(ProviderToolMachine.Phase.SUCCEEDED, ProviderToolMachine.Phase.FAILED, ProviderToolMachine.Phase.CANCELLED,
            ProviderToolMachine.Phase.INTERRUPTED, ProviderToolMachine.Phase.UNKNOWN)
    }
}

private class ProviderToolRunFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Обращение к модели не выполняет внешних действий, кроме списания токенов. Полученный отказ
 * провайдера подтверждает, что ответа нет: запрос заканчивается `FAILED`, и человек может
 * повторить его без восстановления «неизвестного исхода». Потеря ответа (таймаут, обрыв
 * соединения, `408`/`5xx`) и неподтверждённая запись оставляют `UNKNOWN`.
 */
internal fun providerOutcomeUnknown(failure: Exception): Boolean = when {
    // Шлюз не поддерживает протокол инструментов: обращение к провайдеру не выполнялось.
    failure is UnsupportedOperationException -> false
    else -> failure.transportRejection()?.confirmedRejection != true
}

/**
 * Безопасные метаданные сбоя для журнала: идентификатор запроса, класс причины и статус ответа
 * провайдера. Сообщение исключения намеренно не передаётся: `LlmTransportException` несёт в нём
 * фрагмент тела ответа провайдера, который не принадлежит ни журналу, ни интерфейсу.
 */
internal fun diagnosticFields(failure: Throwable, runId: String = ""): Map<String, String> = buildMap {
    if (runId.isNotBlank()) put("runId", runId)
    put("causeType", failure::class.simpleName.orEmpty())
    failure.transportRejection()?.let { putAll(it.logFields()) }
}
