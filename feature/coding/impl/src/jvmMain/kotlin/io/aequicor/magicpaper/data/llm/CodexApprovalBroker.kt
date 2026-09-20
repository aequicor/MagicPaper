package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Routes live JSON-RPC approvals without blocking the app-server reader. */
internal class CodexApprovalBroker(private val notice: (String, String) -> Unit) {
    private data class Pending(
        val view: CodingApproval,
        val wireId: JsonPrimitive,
        val threadId: String,
        val turnId: String,
        val itemId: String,
        val permissions: JsonObject?,
        val denyDecision: String,
        val reply: suspend (JsonObject) -> Unit,
    )

    private val lock = Any()
    private val ids = AtomicLong()
    private val pending = linkedMapOf<String, Pending>()
    private val state = MutableStateFlow<List<CodingApproval>>(emptyList())
    val requests = state.asStateFlow()
    private val pretty = Json { prettyPrint = true }

    fun receive(
        wireId: JsonPrimitive,
        method: String,
        params: JsonObject,
        session: CodingSession,
        item: JsonObject?,
        reply: suspend (JsonObject) -> Unit,
    ): Boolean {
        val network = params["networkApprovalContext"] as? JsonObject
        val kind = when (method) {
            "item/commandExecution/requestApproval" -> if (network != null) CodingApprovalKind.NETWORK else CodingApprovalKind.COMMAND
            "item/fileChange/requestApproval" -> CodingApprovalKind.FILE_CHANGE
            "item/permissions/requestApproval" -> CodingApprovalKind.PERMISSIONS
            else -> return false
        }
        val threadId = params.text("threadId") ?: return false
        val turnId = params.text("turnId") ?: return false
        val itemId = params.text("itemId") ?: return false
        val permissions = params["permissions"] as? JsonObject
        if (kind == CodingApprovalKind.PERMISSIONS && permissions == null) return false
        val command = params.text("command") ?: item?.text("command")
        val changes = item?.get("changes") as? JsonArray
        val available = params["availableDecisions"] as? JsonArray
        val canAllow = (available == null || JsonPrimitive("accept") in available) && when (kind) {
            CodingApprovalKind.COMMAND -> !command.isNullOrBlank()
            CodingApprovalKind.NETWORK -> !network?.text("host").isNullOrBlank()
            CodingApprovalKind.FILE_CHANGE -> !changes.isNullOrEmpty() && changes.all { change ->
                val file = change as? JsonObject ?: return@all false
                file.text("path")?.isNotBlank() == true && file.text("diff") != null &&
                    (file["kind"] as? JsonObject)?.text("type") in listOf("add", "delete", "update")
            }
            CodingApprovalKind.PERMISSIONS -> !permissions.isNullOrEmpty()
        }
        val deny = if (available != null && JsonPrimitive("decline") !in available && JsonPrimitive("cancel") in available) "cancel" else "decline"
        val details = buildString {
            (params.text("cwd") ?: item?.text("cwd"))?.let { append("Рабочая папка: $it\n\n") }
            when (kind) {
                CodingApprovalKind.NETWORK -> append("Адрес: ${network?.text("host").orEmpty()}\nПротокол: ${network?.text("protocol").orEmpty()}")
                CodingApprovalKind.COMMAND -> append(command ?: "Движок не передал команду.")
                CodingApprovalKind.FILE_CHANGE -> {
                    params.text("grantRoot")?.let { append("Запрошенная папка: $it\n\n") }
                    changes.orEmpty().forEach { change ->
                        val file = change as? JsonObject ?: return@forEach
                        append(file.text("path") ?: file.text("filePath") ?: "Файл").append('\n')
                        val operation = file["kind"] as? JsonObject
                        append(when (operation?.text("type")) {
                            "add" -> "Создание файла"
                            "delete" -> "Удаление файла"
                            "update" -> "Изменение файла"
                            else -> "Тип изменения не указан"
                        }).append('\n')
                        operation?.text("move_path")?.let { append("Перемещение в: $it\n") }
                        append(file.text("diff").orEmpty()).append("\n\n")
                    }
                    if (changes.isNullOrEmpty()) append("Движок не передал изменения файлов.")
                }
                CodingApprovalKind.PERMISSIONS -> append(pretty.encodeToString(JsonObject.serializer(), permissions!!))
            }
            (params["additionalPermissions"] as? JsonObject)?.let {
                append("\n\nДополнительный доступ:\n").append(pretty.encodeToString(JsonObject.serializer(), it))
            }
        }.trim()
        synchronized(lock) {
            // A retransmission must not change the operation under an already visible button.
            if (pending.values.any { it.wireId == wireId && it.threadId == threadId }) return true
            val view = CodingApproval("approval-${ids.incrementAndGet()}", session.id, session.projectId,
                session.name, kind, params.text("reason")?.takeIf { it.isNotBlank() }
                    ?: "Движок запросил разрешение перед выполнением действия.", details, canAllow)
            pending[view.id] = Pending(view, wireId, threadId, turnId, itemId, permissions, deny, reply)
            publish()
        }
        notice(threadId, "Ожидается подтверждение: ${params.text("reason") ?: kind.name}\n$details")
        return true
    }

    suspend fun respond(id: String, decision: CodingApprovalDecision) {
        val request = synchronized(lock) {
            val current = pending[id] ?: return
            if (current.view.submitting || (decision == CodingApprovalDecision.ALLOW_ONCE && !current.view.canAllow)) return
            current.copy(view = current.view.copy(submitting = true)).also { pending[id] = it; publish() }
        }
        val allowed = decision == CodingApprovalDecision.ALLOW_ONCE
        val result = buildJsonObject {
            if (request.view.kind == CodingApprovalKind.PERMISSIONS) {
                put("permissions", if (allowed) request.permissions!! else buildJsonObject {})
                put("scope", "turn")
            } else put("decision", if (allowed) "accept" else request.denyDecision)
        }
        // Record the user's choice before the server can complete and close the event stream.
        notice(request.threadId, if (allowed) "Пользователь разрешил действие: ${request.view.details}"
            else "Пользователь отклонил действие: ${request.view.details}")
        try {
            request.reply(buildJsonObject { put("id", request.wireId); put("result", result) })
            synchronized(lock) { pending.remove(id); publish() }
        } catch (error: Exception) {
            notice(request.threadId, "Не удалось подтвердить передачу решения движку.")
            // Sending can have an uncertain result. Never offer a second, conflicting decision.
            synchronized(lock) {
                if (pending[id] === request) {
                    pending[id] = request.copy(view = request.view.copy(error = "Не удалось передать решение. Остановите запрос и запустите его снова."))
                    publish()
                }
            }
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    fun resolved(threadId: String, wireId: JsonElement) = remove { it.threadId == threadId && it.wireId == wireId }
    fun clearTurn(threadId: String, turnId: String? = null) = remove { it.threadId == threadId && (turnId == null || it.turnId == turnId) }
    fun completeItem(threadId: String, itemId: String) = remove { it.threadId == threadId && it.itemId == itemId }
    fun clear() = remove { true }
    fun contains(threadId: String, wireId: JsonPrimitive): Boolean = synchronized(lock) {
        pending.values.any { it.threadId == threadId && it.wireId == wireId }
    }
    private fun remove(predicate: (Pending) -> Boolean) = synchronized(lock) {
        pending.entries.removeAll { predicate(it.value) }
        publish()
    }
    private fun publish() { state.value = pending.values.map { it.view } }
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
