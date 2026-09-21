package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CatalogModelLimits
import io.aequicor.magicpaper.domain.ModelLimitCatalog
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Пределы моделей из каталога, который поставляется вместе с движком Pi
 * (`@earendil-works/pi-ai/dist/providers/data`, данные models.dev). Это факт о конкретном
 * эндпоинте провайдера, а не догадка по имени семейства: совпадение требует того же хоста
 * и того же идентификатора модели, а противоречие между записями означает «факт неизвестен».
 *
 * Каталог версионируется вместе с движком, поэтому индекс читается один раз и перечитывается
 * только после изменения файлов.
 */
class PiEngineModelLimits(
    private val dataDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ModelLimitCatalog {

    private data class Entry(
        val host: String,
        val modelId: String,
        val contextWindow: Int?,
        val maxOutputTokens: Int?,
    )

    private data class Index(val signature: String, val entries: List<Entry>)

    @Volatile
    private var index: Index? = null

    override fun limits(baseUrl: String, modelIds: Collection<String>): Map<String, CatalogModelLimits> {
        val host = endpointHost(baseUrl) ?: return emptyMap()
        if (modelIds.isEmpty()) return emptyMap()
        val byModel = index().entries.asSequence()
            .filter { it.host == host }
            .groupBy { it.modelId.lowercase() }
        if (byModel.isEmpty()) return emptyMap()
        val facts = LinkedHashMap<String, CatalogModelLimits>()
        for (id in modelIds) {
            val entries = byModel[id.lowercase()] ?: continue
            val limits = CatalogModelLimits(
                contextWindow = entries.consensus { it.contextWindow },
                maxOutputTokens = entries.consensus { it.maxOutputTokens },
            )
            if (!limits.isUnknown) facts[id] = limits
        }
        return facts
    }

    /** Единственное объявленное значение среди записей эндпоинта; расхождение не выдаётся за факт. */
    private fun List<Entry>.consensus(select: (Entry) -> Int?): Int? =
        mapNotNull(select).distinct().singleOrNull()

    private fun index(): Index {
        val signature = signature()
        index?.let { if (it.signature == signature) return it }
        return read(signature).also { index = it }
    }

    private fun files(): List<File> =
        dataDir.listFiles { file -> file.isFile && file.name.endsWith(".json") && !file.name.startsWith(".") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun signature(): String =
        files().joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }

    private fun read(signature: String): Index {
        val entries = ArrayList<Entry>()
        for (file in files()) {
            try {
                val root = json.parseToJsonElement(file.readText()) as? JsonObject
                if (root == null) {
                    AppLog.error("EngineModelLimits", "catalog_shape_unexpected",
                        mapOf("provider" to file.name, "reason" to "not_an_object", "recovery" to "skipped_file"))
                    continue
                }
                // Корень файла — разделы по диалектам API (`openai-completions`, `anthropic-messages`, …),
                // внутри раздела — модели: идентификатор → факты провайдера.
                for (section in root.values) {
                    val models = section as? JsonObject ?: continue
                    for (value in models.values) {
                        val model = value as? JsonObject ?: continue
                        val id = model.text("id")?.takeIf { it.isNotBlank() } ?: continue
                        val host = endpointHost(model.text("baseUrl").orEmpty()) ?: continue
                        entries += Entry(
                            host = host,
                            modelId = id,
                            contextWindow = model.number("contextWindow"),
                            maxOutputTokens = model.number("maxTokens"),
                        )
                    }
                }
            } catch (failure: Exception) {
                // Частичный каталог лучше полного отказа: остальные провайдеры остаются доступны,
                // а причина видна в диагностике без содержимого файла.
                AppLog.error("EngineModelLimits", "catalog_read_failed", failure,
                    mapOf("provider" to file.name, "recovery" to "skipped_file"))
            }
        }
        AppLog.debug("EngineModelLimits", "catalog_indexed",
            mapOf("entries" to entries.size.toString(), "source" to "pi-ai-providers-data"))
        return Index(signature, entries)
    }
}

/**
 * Факты о моделях из установленного движка Pi; `null`, когда движок не установлен
 * (web, Android или свежая установка) и пределы остаются зоной ответственности провайдера.
 */
fun engineModelLimits(
    rootDir: File = File(File(System.getProperty("user.home"), ".MagicPaper"), "coding"),
): ModelLimitCatalog? = resolvePiAiDist(File(rootDir, "prefix"))
    ?.let { File(it, "providers/data") }
    ?.takeIf { it.isDirectory }
    ?.let { PiEngineModelLimits(it) }

/** Каталог `@earendil-works/pi-ai/dist` установленного движка: локальный пакет, глобальный или ничего. */
internal fun resolvePiAiDist(prefix: File): File? {
    val agent = File(prefix, "node_modules/@earendil-works/pi-coding-agent")
    val dist = "node_modules/@earendil-works/pi-ai/dist"
    return listOf(File(agent, dist), File(prefix, dist)).firstOrNull { File(it, "index.js").isFile }
}

/**
 * Хост эндпоинта: путь и версия API (`/v1`, `/compatible-mode/v1`) различаются у одного
 * провайдера и не меняют заявленные пределы модели, поэтому в совпадении не участвуют.
 */
private fun endpointHost(baseUrl: String): String? {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return null
    val authority = trimmed.substringAfter("://", "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .trim()
    if (authority.isEmpty()) return null
    return authority.substringAfterLast('@').lowercase().removeSuffix(".")
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.replace("_", "")?.toIntOrNull()
}
