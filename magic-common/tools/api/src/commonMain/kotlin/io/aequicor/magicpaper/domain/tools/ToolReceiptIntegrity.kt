package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.PlanningDiagnostics
import io.ktor.util.Digest
import kotlinx.serialization.json.*

/** Canonical object order makes aliases/order-independent retries identify the same command. */
suspend fun toolArgumentsFingerprint(arguments: JsonElement): String {
    val digest = Digest("SHA-256")
    digest += StringBuilder().appendCanonical(arguments).toString().encodeToByteArray()
    return digest.build().toHexString()
}

/**
 * The text `toString()` gives the same tree with every object's keys sorted, written into one buffer. That `toString()`
 * renders each nested value on its own and copies it into its parent, once per level: verifying a payload of tens of
 * megabytes cost several times more than reading it. Every recorded fingerprint depends on this exact text, which
 * `ToolReceiptIntegrityTest` holds to the tree's own rendering.
 */
private fun StringBuilder.appendCanonical(value: JsonElement): StringBuilder = apply {
    when (value) {
        is JsonObject -> {
            append('{')
            value.entries.sortedBy { it.key }.forEachIndexed { index, (key, item) ->
                if (index > 0) append(',')
                append(JsonPrimitive(key).toString()).append(':').appendCanonical(item)
            }
            append('}')
        }
        is JsonArray -> {
            append('[')
            value.forEachIndexed { index, item -> if (index > 0) append(','); appendCanonical(item) }
            append(']')
        }
        // Quoted and escaped for a string; a number, boolean or null exactly as it was written.
        is JsonPrimitive -> append(value.toString())
    }
}

private val credentialField = Regex("(?i)^(?:authorization|proxy[_-]?authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|password|passwd|secret|client[_-]?secret|private[_-]?key|token|cookie|set[_-]?cookie)$")

/** Preserve JSON shape and non-secret arguments so receipts remain useful to reconciliation. */
fun redactToolJson(value: JsonElement, secrets: Set<String> = emptySet()): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key, item) ->
        // `PlanningQuestion.secret` is a Boolean capability flag, not a credential value.
        if (credentialField.matches(key) && item != JsonNull && (item !is JsonPrimitive || item.isString))
            JsonPrimitive("[скрыто]") else redactToolJson(item, secrets)
    })
    is JsonArray -> JsonArray(value.map { redactToolJson(it, secrets) })
    is JsonPrimitive -> if (value.isString) JsonPrimitive(PlanningDiagnostics.redact(value.content, secrets)) else value
}

/** Fingerprint is computed before redaction, including during migration of old raw receipts. */
suspend fun ToolReceipt.forPersistence(secrets: Set<String> = emptySet()): ToolReceipt = copy(
    arguments = redactToolJson(arguments, secrets).jsonObject,
    argumentFingerprint = argumentFingerprint.ifBlank { toolArgumentsFingerprint(arguments) },
    result = redactToolJson(if (toolId == "questionnaire") redactQuestionnaireToolResult(arguments, result) else result, secrets),
    error = PlanningDiagnostics.redact(error, secrets),
    summary = PlanningDiagnostics.redact(summary, secrets),
    title = title?.let { PlanningDiagnostics.redact(it, secrets) },
)

fun ToolReceipt.validateUpdate(next: ToolReceipt) {
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
    require(toolId !in setOf("image.generate", "video.generate") || phase != ToolPhase.FAILED ||
        next.phase == phase && next.result == result && next.error == error) {
        "Подтверждённый исход генерации нельзя заменить"
    }
}

/** Schema flags describe which answer fields must never enter receipts or transcript previews. */
fun questionnaireSecretIds(arguments: JsonObject): Set<String> = (arguments["questions"] as? JsonArray).orEmpty().mapNotNull { item ->
    val question = item as? JsonObject ?: return@mapNotNull null
    if ((question["secret"] as? JsonPrimitive)?.booleanOrNull == true) (question["id"] as? JsonPrimitive)?.contentOrNull else null
}.toSet()

fun redactQuestionnaireToolResult(arguments: JsonObject, result: JsonElement): JsonElement {
    val secretIds = questionnaireSecretIds(arguments)
    if (secretIds.isEmpty() || result !is JsonArray) return result
    return JsonArray(result.map { item ->
        val answer = item as? JsonObject
        val id = (answer?.get("questionId") as? JsonPrimitive)?.contentOrNull
        if (id in secretIds) buildJsonObject { put("questionId", id); put("text", "[скрыто]") } else item
    })
}
