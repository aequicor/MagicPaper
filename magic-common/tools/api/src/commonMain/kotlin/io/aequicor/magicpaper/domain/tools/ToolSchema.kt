package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
fun toolSchema(descriptor: SerialDescriptor): JsonObject {
    val schema = buildJsonObject {
    when (descriptor.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> put("type", "string")
        PrimitiveKind.BOOLEAN -> put("type", "boolean")
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> put("type", "integer")
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> put("type", "number")
        SerialKind.ENUM -> { put("type", "string"); put("enum", JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) })) }
        StructureKind.LIST -> { put("type", "array"); put("items", toolSchema(descriptor.getElementDescriptor(0))) }
        StructureKind.MAP -> { put("type", "object"); put("additionalProperties", toolSchema(descriptor.getElementDescriptor(1))) }
        else -> {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") { repeat(descriptor.elementsCount) { put(descriptor.getElementName(it), toolSchema(descriptor.getElementDescriptor(it))) } }
            put("required", JsonArray((0 until descriptor.elementsCount).filter { !descriptor.isElementOptional(it) && !descriptor.getElementDescriptor(it).isNullable }.map { JsonPrimitive(descriptor.getElementName(it)) }))
        }
    }
    }
    return if (!descriptor.isNullable) schema else buildJsonObject {
        put("anyOf", buildJsonArray { add(schema); add(buildJsonObject { put("type", "null") }) })
    }
}
