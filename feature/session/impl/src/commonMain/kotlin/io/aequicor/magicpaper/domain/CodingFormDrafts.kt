package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import io.aequicor.magicpaper.util.Id
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Entity-owned editing forms share DraftSession's durable actor and version fences. */
internal class CodingFormDrafts(private val repository: DraftRepository, private val scope: CoroutineScope) {
    private var closed = false
    private val removed = mutableSetOf<String>()
    private val rejected = mutableMapOf<String, CodingTextForm>()
    private val entries = mutableMapOf<String, CodingTextForm>()
    private fun segment(id: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(id)) + "/"
    private fun prefix(projectId: String) = "coding-form/" + segment(projectId)
    private fun prefix(projectId: String, sessionId: String) = prefix(projectId) + segment(sessionId)

    fun rename(session: CodingSession) = entry(prefix(session.projectId, session.id) + "rename", session.name, session.name)
    fun schedule(session: CodingSession, plan: Plan, rule: ScheduledMessage) =
        entry(prefix(session.projectId, session.id) + "schedule/" + segment(plan.id) + segment(rule.runId) + segment(rule.id), "")
    private fun entry(key: String, initial: String, baseline: String? = null): CodingTextForm {
        fun create() = CodingTextForm(DraftSession(repository, key, CodingTextInput.serializer(), CodingTextInput(initial, baseline = baseline), scope), scope)
        if (closed || removed.any(key::startsWith)) return rejected.getOrPut(key) { create().also { it.revoke() } }
        return entries.getOrPut(key, ::create)
    }
    suspend fun remove(projectId: String, sessionIds: Set<String>? = null) {
        val prefixes = sessionIds?.map { prefix(projectId, it) } ?: listOf(prefix(projectId))
        removed.addAll(prefixes)
        val cached = entries.keys.filter { key -> prefixes.any(key::startsWith) }
        cached.forEach { entries.remove(it)?.revoke() }
        (prefixes.flatMap { repository.keys(it) } + cached).distinct().forEach { repository.remove(it) }
    }
    suspend fun flush() {
        var failure: Exception? = null
        entries.values.toList().forEach { form ->
            try { form.draft.awaitSaved() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (failure == null) failure = error else failure?.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
    fun revoke() { closed = true; entries.values.forEach { it.revoke() }; entries.clear() }
    fun resumeAfterReset() { closed = false; removed.clear(); rejected.clear() }
}

@Serializable
internal data class CodingTextInput(val text: String, val operationId: String = Id.new(), val baseline: String? = null)

internal data class CodingTextFormState(val busy: Boolean = false, val error: String? = null, val completed: Long = 0, val available: Boolean = true)

internal class CodingTextForm(val draft: DraftSession<CodingTextInput>, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(CodingTextFormState())
    val state = mutableState.asStateFlow()
    private var acceptedVersion: Long? = null
    private var pendingCompletion: Pair<Long, CodingTextInput>? = null
    fun update(text: String) { if (mutableState.value.available) draft.update(draft.state.value.value.copy(text = text, operationId = Id.new())) }
    fun revoke() { draft.revoke(); mutableState.value = mutableState.value.copy(available = false) }

    /** A failed cleanup retries cleanup only, never the already accepted command. */
    fun submit(replacement: (String) -> String = { "" }, action: suspend (String, String, String?) -> Boolean) {
        if (!mutableState.value.available || mutableState.value.busy) return
        if (pendingCompletion != null) { retry(); return }
        if (!draft.state.value.loaded || draft.state.value.value.text.isBlank()) return
        // Persist an unchanged initial/restored form before crossing the command boundary.
        if (draft.state.value.version == 0L) draft.update(draft.state.value.value)
        val captured = draft.state.value
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        scope.launch {
            try {
                draft.awaitSaved()
                if (acceptedVersion != captured.version) {
                    if (!action(captured.value.text, captured.value.operationId, captured.value.baseline)) {
                        mutableState.value = mutableState.value.copy(error = "Не удалось выполнить действие. Черновик сохранён.")
                        return@launch
                    }
                    acceptedVersion = captured.version
                }
                val nextText = replacement(captured.value.text)
                advanceRenameBaseline(captured.value, nextText)
                val next = CodingTextInput(nextText, baseline = captured.value.baseline?.let { nextText })
                pendingCompletion = captured.version + 1 to next
                val cleared = draft.clearIfUnchanged(captured.version, next)
                advanceRenameBaseline(captured.value, nextText)
                if (cleared) completeIfCurrent() else pendingCompletion = null
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (failure !is StorageException || !failure.committed) pendingCompletion = null
                if (failure is StorageException) logPersistenceFailure("coding.forms", "submit.failed", failure)
                else AppLog.error("coding.forms", "submit.failed", failure)
                mutableState.value = mutableState.value.copy(error = if (acceptedVersion == captured.version)
                    "Действие выполнено. Не удалось очистить черновик. Повторите сохранение." else
                    "Не удалось выполнить действие. Проверьте данные и повторите.")
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun discard(replacement: String) {
        val captured = draft.state.value
        if (!mutableState.value.available || mutableState.value.busy || !captured.loaded) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        scope.launch {
            try {
                val next = CodingTextInput(replacement, baseline = captured.value.baseline?.let { replacement })
                pendingCompletion = captured.version + 1 to next
                if (draft.clearIfUnchanged(captured.version, next)) completeIfCurrent()
                else pendingCompletion = null
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (failure !is StorageException || !failure.committed) pendingCompletion = null
                if (failure is StorageException) logPersistenceFailure("coding.forms", "discard.failed", failure)
                else AppLog.error("coding.forms", "discard.failed", failure)
                mutableState.value = mutableState.value.copy(error = "Не удалось удалить черновик. Повторите отмену.")
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun retry() {
        if (!mutableState.value.available || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        scope.launch {
            try {
                draft.awaitSaved()
                mutableState.value = mutableState.value.copy(error = null)
                completeIfCurrent()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is StorageException) logPersistenceFailure("coding.forms", "retry.failed", failure)
                else AppLog.error("coding.forms", "retry.failed", failure)
                mutableState.value = mutableState.value.copy(error = "Не удалось сохранить черновик. Повторите сохранение.")
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    private fun advanceRenameBaseline(captured: CodingTextInput, savedName: String) {
        val latest = draft.state.value.value
        if (captured.baseline != null && latest.operationId != captured.operationId &&
            latest.baseline == captured.baseline && latest.baseline != savedName) {
            // New typing starts from the rename that just committed, including typing during clear.
            draft.update(latest.copy(baseline = savedName))
        }
    }

    private fun completeIfCurrent() {
        val pending = pendingCompletion ?: return
        val current = draft.state.value
        if (current.version == pending.first && current.value == pending.second)
            mutableState.value = mutableState.value.copy(completed = mutableState.value.completed + 1, error = null)
        pendingCompletion = null
    }
}
