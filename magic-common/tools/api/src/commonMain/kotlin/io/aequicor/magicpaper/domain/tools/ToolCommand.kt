package io.aequicor.magicpaper.domain.tools

import kotlinx.serialization.json.*

interface ToolCommand<A, R> {
    val definition: ToolDefinition
    fun decode(arguments: JsonObject): A
    suspend fun execute(context: ToolExecutionContext, operationId: String, args: A): R
    fun encode(result: R): JsonElement
}

class JsonToolCommand(
    override val definition: ToolDefinition,
    private val action: suspend (ToolExecutionContext, String, JsonObject) -> JsonElement,
) : ToolCommand<JsonObject, JsonElement> {
    override fun decode(arguments: JsonObject): JsonObject {
        try { validateToolArguments(definition.schema, arguments) }
        catch (failure: IllegalArgumentException) { throw ToolArgumentRejection(failure.message ?: "Некорректные аргументы инструмента") }
        // This conditional schema rule is independent of runtime state and precedes any intent.
        if (definition.id == "stage.resolve" && arguments["action"]?.jsonPrimitive?.content == "CONTINUE")
            require(!arguments["reason"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "Укажите причину продолжения" }
        return arguments
    }
    override suspend fun execute(context: ToolExecutionContext, operationId: String, args: JsonObject) = action(context, operationId, args)
    override fun encode(result: JsonElement) = result
}

/** Validate the JSON subset used by the catalog before invoking any receiver. */
fun validateToolArguments(schema: JsonObject, value: JsonElement, path: String = "arguments") {
    (schema["anyOf"] as? JsonArray)?.let { variants ->
        require(variants.any { runCatching { validateToolArguments(it.jsonObject, value, path) }.isSuccess }) { "$path: неверный тип" }
        return
    }
    val type = schema["type"]?.jsonPrimitive?.content
    require(when (type) {
        "null" -> value == JsonNull
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
        "object" -> value is JsonObject; "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        else -> true
    }) { "$path: ожидается $type" }
    schema["enum"]?.jsonArray?.let { require(value in it) { "$path: недопустимое значение" } }
    if (value is JsonObject) {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        (schema["required"] as? JsonArray).orEmpty().forEach { require(it.jsonPrimitive.content in value) { "$path: отсутствует ${it.jsonPrimitive.content}" } }
        if (schema["additionalProperties"] == JsonPrimitive(false)) require(value.keys.all { it in properties }) { "$path: неизвестные аргументы" }
        properties.forEach { (key, spec) -> value[key]?.let { validateToolArguments(spec.jsonObject, it, "$path.$key") } }
    }
    if (value is JsonArray) {
        schema["minItems"]?.jsonPrimitive?.intOrNull?.let { require(value.size >= it) { "$path: пустой список" } }
        schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { require(value.size <= it) { "$path: слишком много элементов (максимум $it)" } }
        (schema["items"] as? JsonObject)?.let { spec -> value.forEach { validateToolArguments(spec, it, path) } }
    }
}
