package io.aequicor.magicpaper.data.computer

import kotlinx.serialization.json.*
import java.util.UUID

/** One native connection per grant. Native element references never survive revocation or restart. */
internal interface ApplicationDesktop : AutoCloseable {
    fun request(args: JsonObject, checkActive: () -> Unit): JsonObject
}

/** Serialised by DesktopComputerUse with the desktop tool; no global input fallback exists. */
internal class ApplicationUse(
    private val native: ApplicationDesktop,
    private val clock: () -> Long,
) : AutoCloseable {
    private data class Snapshot(val id: String, val window: String, val created: Long, val elements: Map<String, Set<String>>)
    private var snapshot: Snapshot? = null

    fun execute(args: JsonObject, control: Boolean, beforeMutation: () -> Unit = {}, checkActive: () -> Unit): JsonObject {
        computerRequire(args.keys.all { it in ApplicationTool.fields }) { "Неизвестный параметр application" }
        val action = args.requiredString("action")
        val format = args.optionalString("format") ?: "jpeg"
        computerRequire(format in listOf("jpeg", "png")) { "format: jpeg или png" }
        computerRequire(action in ApplicationTool.actions) { "Неизвестное действие application" }
        checkActive()
        if (action == "windows") {
            snapshot = null
            return toolText(native.request(args, checkActive).toString())
        }
        val window = args.requiredString("window_id")
        if (action !in listOf("inspect", "screenshot")) {
            check(control) { "Для приложений разрешён только просмотр. Измените доступ в настройках." }
            val previous = snapshot ?: error("Сначала вызовите inspect для выбранного окна.")
            check(previous.window == window && previous.id == args.requiredString("snapshot_id") &&
                clock() - previous.created <= 30_000_000_000L) { "Состояние устарело. Вызовите inspect и проверьте окно." }
            check(action in previous.elements[args.requiredString("element_id")].orEmpty()) {
                "Элемент не поддерживает это фоновое действие. Вызовите inspect; общей мышью действие не заменяется."
            }
            if (action == "set_value") computerRequire(args.requiredString("text").length <= 10_000) { "text: не более 10000 символов" }
            // Consume BEFORE the external call, including failures with unknown outcome.
            snapshot = null
            checkActive()
            beforeMutation()
            native.request(args, checkActive)
        }
        // A screenshot alone cannot mint input authority; inspect returns fresh element references.
        snapshot = null
        val result = native.request(buildJsonObject { put("action", if (action == "screenshot") "screenshot" else "inspect"); put("window_id", window) }, checkActive)
        checkActive()
        val nodes = result["elements"] as? JsonArray
        val id = UUID.randomUUID().toString()
        if (nodes != null) snapshot = Snapshot(id, window, clock(), nodes.associate { node ->
            node.jsonObject.requiredString("element_id") to node.jsonObject["actions"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        })
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", JsonObject(result.filterKeys { it != "png" } + if (nodes != null) mapOf("snapshot_id" to JsonPrimitive(id)) else emptyMap()).toString())
                })
                result.optionalString("png")?.let { png -> add(buildJsonObject {
                    val encoded = encodeScreenshot(java.util.Base64.getDecoder().decode(png), format)
                    put("type", "image"); put("mimeType", encoded.mimeType); put("data", java.util.Base64.getEncoder().encodeToString(encoded.bytes))
                }) }
            })
            put("isError", false)
        }
    }

    fun reference(): Pair<String, Long>? = snapshot?.let { it.id to it.created }

    override fun close() = native.close()
}

