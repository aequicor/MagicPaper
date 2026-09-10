package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.PlanningDiagnostics
import io.ktor.util.Digest
import kotlinx.serialization.json.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Only use at a boundary known to precede effects (or after confirmed safe compensation). */
interface RejectedToolCall
class ToolArgumentRejection(message: String) : IllegalArgumentException(message), RejectedToolCall
class ToolStateRejection(message: String) : IllegalStateException(message), RejectedToolCall

@OptIn(ExperimentalContracts::class)
inline fun requireTool(condition: Boolean, message: () -> String) {
    contract { returns() implies condition }
    if (!condition) throw ToolArgumentRejection(message())
}

@OptIn(ExperimentalContracts::class)
inline fun checkTool(condition: Boolean, message: () -> String) {
    contract { returns() implies condition }
    if (!condition) throw ToolStateRejection(message())
}

/** Canonical object order makes aliases/order-independent retries identify the same command. */
internal suspend fun toolArgumentsFingerprint(arguments: JsonElement): String {
    val digest = Digest("SHA-256")
    digest += canonicalToolJson(arguments).toString().encodeToByteArray()
    return digest.build().toHexString()
}

private fun canonicalToolJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonicalToolJson(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalToolJson))
    else -> value
}

private val credentialField = Regex("(?i)^(?:authorization|proxy[_-]?authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|password|passwd|secret|client[_-]?secret|private[_-]?key|token|cookie|set[_-]?cookie)$")

/** Preserve JSON shape and non-secret arguments so receipts remain useful to reconciliation. */
internal fun redactToolJson(value: JsonElement, secrets: Set<String> = emptySet()): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key, item) ->
        // `PlanningQuestion.secret` is a Boolean capability flag, not a credential value.
        if (credentialField.matches(key) && item != JsonNull && (item !is JsonPrimitive || item.isString))
            JsonPrimitive("[скрыто]") else redactToolJson(item, secrets)
    })
    is JsonArray -> JsonArray(value.map { redactToolJson(it, secrets) })
    is JsonPrimitive -> if (value.isString) JsonPrimitive(PlanningDiagnostics.redact(value.content, secrets)) else value
}

/** Fingerprint is computed before redaction, including during migration of old raw receipts. */
internal suspend fun ToolReceipt.forPersistence(secrets: Set<String> = emptySet()): ToolReceipt = copy(
    arguments = redactToolJson(arguments, secrets).jsonObject,
    argumentFingerprint = argumentFingerprint.ifBlank { toolArgumentsFingerprint(arguments) },
    result = redactToolJson(result, secrets),
    error = PlanningDiagnostics.redact(error, secrets),
    summary = PlanningDiagnostics.redact(summary, secrets),
    title = title?.let { PlanningDiagnostics.redact(it, secrets) },
)

internal fun ToolReceipt.validateUpdate(next: ToolReceipt) {
    require(id == next.id && toolId == next.toolId && argumentFingerprint == next.argumentFingerprint &&
        (operationId.isBlank() || operationId == next.operationId) && runtimeGeneration == next.runtimeGeneration) {
        "Идентификатор операции уже использован с другими аргументами или поколением"
    }
    require(phase != ToolPhase.SUCCEEDED || (next.phase == ToolPhase.SUCCEEDED && result == next.result)) {
        "Подтверждённый результат операции нельзя заменить"
    }
    require(!native || phase !in setOf(ToolPhase.FAILED, ToolPhase.CANCELLED) ||
        next.native && next.phase == phase && next.result == result && next.error == error) {
        "Подтверждённый исход нативного вызова нельзя заменить"
    }
}
