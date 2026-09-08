package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** The reader stays free to process interruption/resolution while the user edits their answers. */
internal class CodexQuestionnaireBroker(
    private val scope: CoroutineScope,
    private val registry: RuntimeQuestionnaires,
    private val notice: (String, String) -> Unit,
    private val failed: (String, Throwable) -> Unit,
) {
    private data class Key(val threadId: String, val wireId: JsonPrimitive)
    private data class Pending(val turnId: String, val itemId: String, val job: Job)
    private val pending = ConcurrentHashMap<Key, Pending>()
    private val received = ConcurrentHashMap.newKeySet<Key>()

    fun receive(id: JsonPrimitive, method: String, params: JsonObject, session: CodingSession,
        reply: suspend (JsonObject) -> Unit): Boolean {
        if (method != "item/tool/requestUserInput") return false
        val threadId = params.text("threadId") ?: return false
        val turnId = params.text("turnId") ?: return false
        val itemId = params.text("itemId") ?: return false
        val questions = runCatching {
            params["questions"]!!.jsonArray.map { element ->
                val q = element.jsonObject
                val options = (q["options"] as? JsonArray).orEmpty().map { option ->
                    val value = option.jsonObject
                    val label = value.text("label") ?: error("Missing option")
                    QuestionOption(label, label, value.text("description").orEmpty())
                }
                PlanningQuestion(q.text("id") ?: error("Missing question id"), q.text("question") ?: error("Missing question"),
                    if (options.isEmpty()) QuestionKind.TEXT else QuestionKind.SINGLE, options,
                    secret = q["isSecret"]?.jsonPrimitive?.booleanOrNull == true)
            }.also { list -> require(list.isNotEmpty() && list.all { it.id.isNotBlank() && it.title.isNotBlank() } && list.distinctBy { it.id }.size == list.size) }
        }.getOrNull() ?: return false
        val key = Key(threadId, id)
        if (!received.add(key)) return true
        val requestId = "runtime:codex:$threadId:$turnId:$itemId:$id"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val request = UserInteractionRequest(requestId, session.projectId, session.id, InteractionKind.RUNTIME, questions,
                    ownerSessionId = session.parentSessionId ?: session.id, context = session.name, createdAt = System.currentTimeMillis())
                val answers = registry.ask(request)
                notice(threadId, "Ответы пользователя:\n" + interactionAnswerText(questions, answers, redactSecrets = true))
                reply(buildJsonObject { put("id", id); put("result", buildJsonObject {
                    put("answers", buildJsonObject { answers.forEach { a ->
                        put(a.questionId, buildJsonObject { put("answers", buildJsonArray {
                            if (!a.skipped) { a.selected.forEach { add(JsonPrimitive(it)) }; if (a.text.isNotBlank()) add(JsonPrimitive(a.text)) }
                        }) })
                    } })
                }) })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(threadId, e) }
            finally { pending.remove(key) }
        }
        if (pending.putIfAbsent(key, Pending(turnId, itemId, job)) != null) job.cancel() else job.start()
        return true
    }
    fun contains(threadId: String, id: JsonPrimitive) = pending.containsKey(Key(threadId, id))
    fun resolved(threadId: String, id: JsonElement) = remove { key, _ -> key.threadId == threadId && key.wireId == id }
    fun clearTurn(threadId: String, turnId: String? = null) = remove { key, p -> key.threadId == threadId && (turnId == null || p.turnId == turnId) }
    fun completeItem(threadId: String, itemId: String) = remove { key, p -> key.threadId == threadId && p.itemId == itemId }
    fun clear() { remove { _, _ -> true }; received.clear() }
    private fun remove(predicate: (Key, Pending) -> Boolean) {
        pending.entries.toList().filter { predicate(it.key, it.value) }.forEach { (key, value) ->
            if (pending.remove(key, value)) value.job.cancel()
        }
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
