package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.DeclaredReasoning
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.ReasoningEffort
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Объявления провайдера из его каталога `/models`: что модель перечислила —
 * то и показываем на ручке; сервер без объявлений остаётся на эвристике.
 * Проверка идёт по тому же пути, что и живые каталоги — [parseProviderModels].
 */
class ModelDirectoryReasoningTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun declared(body: String, id: String): DeclaredReasoning? =
        parseProviderModels(json, body, ProviderType.OPENAI_COMPATIBLE).single { it.id == id }.declared

    @Test
    fun parsesDeclaredEffortsAndMandatory() {
        val body = """
            {"data":[
              {"id":"kimi-k3","supported_parameters":["reasoning"],
               "reasoning":{"mandatory":true,"supported_efforts":["low","high","max"]}},
              {"id":"gpt-5.2","supported_parameters":["reasoning","tools"],
               "reasoning":{"supported_efforts":["minimal","low","medium","high"]}},
              {"id":"llama3.2","supported_parameters":["tools","response_format"]},
              {"id":"phi4"}
            ]}
        """.trimIndent()

        assertEquals(
            DeclaredReasoning(
                efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                mandatory = true,
            ),
            declared(body, "kimi-k3"),
        )
        assertEquals(
            DeclaredReasoning(
                efforts = setOf(
                    ReasoningEffort.MINIMAL,
                    ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                ),
            ),
            declared(body, "gpt-5.2"),
        )
        assertEquals(DeclaredReasoning.None, declared(body, "llama3.2"), "перечень органов без reasoning — ручки нет")
        assertNull(declared(body, "phi4"), "сервер ничего не объявил — остаётся эвристика")
    }

    @Test
    fun unknownLevelNamesAreNotInvented() {
        val body = """{"data":[{"id":"m","reasoning":{"supported_efforts":["low","turbo","high"]}}]}"""
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), declared(body, "m")?.efforts)
    }

    @Test
    fun discoveryUsesDeclarationsForLevels() {
        val body = """{"data":[{"id":"glm-5.2","reasoning":{"supported_efforts":["high","max"]}}]}"""
        val model = parseProviderModels(json, body, ProviderType.OPENAI_COMPATIBLE).single()

        assertEquals(listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX), model.levels)
        assertTrue(model.supportsEffort)
        assertEquals(DeclaredReasoning(efforts = setOf(ReasoningEffort.HIGH, ReasoningEffort.MAX)), model.declared)
    }

    @Test
    fun bareIdListLeavesModelOnHeuristics() {
        val body = """{"data":[{"id":"qwen3:8b","object":"model"},{"id":"llama3.2"}]}"""
        val found = parseProviderModels(json, body, ProviderType.OPENAI_COMPATIBLE)

        assertEquals(listOf("llama3.2", "qwen3:8b"), found.map { it.id }, "список остаётся пригодным")
        assertTrue(found.all { it.declared == null }, "без объявлений ничего не выдумываем")
    }

    @Test
    fun brokenAnswerFailsInsteadOfPretendingTheCatalogIsEmpty() {
        assertFailsWith<SerializationException> {
            parseProviderModels(json, "не json", ProviderType.OPENAI_COMPATIBLE)
        }
    }
}
