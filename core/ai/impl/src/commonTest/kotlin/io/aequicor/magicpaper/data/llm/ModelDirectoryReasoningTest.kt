package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.DeclaredReasoning
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Объявления провайдера из его каталога `/models`: что модель перечислила —
 * то и показываем на ручке; сервер без объявлений остаётся на эвристике.
 */
class ModelDirectoryReasoningTest {

    private val json = Json { ignoreUnknownKeys = true }

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

        val declared = parseDeclaredReasoning(json, body, "data")

        assertEquals(
            DeclaredReasoning(
                efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                mandatory = true,
            ),
            declared["kimi-k3"],
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
            declared["gpt-5.2"],
        )
        assertEquals(DeclaredReasoning.None, declared["llama3.2"], "перечень органов без reasoning — ручки нет")
        assertNull(declared["phi4"], "сервер ничего не объявил — остаётся эвристика")
    }

    @Test
    fun unknownLevelNamesAreNotInvented() {
        val body = """{"data":[{"id":"m","reasoning":{"supported_efforts":["low","turbo","high"]}}]}"""
        val declared = parseDeclaredReasoning(json, body, "data")
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), declared.getValue("m").efforts)
    }

    @Test
    fun discoveryUsesDeclarationsForLevels() {
        val body = """{"data":[{"id":"glm-5.2","reasoning":{"supported_efforts":["high","max"]}}]}"""
        val declared = parseDeclaredReasoning(json, body, "data")
        val found = ModelDefaults.discover(ProviderType.OPENAI_COMPATIBLE, parseIdList(json, body, "data"), declared)

        val model = found.single()
        assertEquals(listOf(ReasoningEffort.HIGH, ReasoningEffort.MAX), model.levels)
        assertTrue(model.supportsEffort)
        assertEquals(DeclaredReasoning(efforts = setOf(ReasoningEffort.HIGH, ReasoningEffort.MAX)), model.declared)
    }

    @Test
    fun bareIdListYieldsNoDeclarations() {
        val body = """{"data":[{"id":"qwen3:8b","object":"model"},{"id":"llama3.2"}]}"""
        assertTrue(parseDeclaredReasoning(json, body, "data").isEmpty())
        assertTrue(parseDeclaredReasoning(json, "не json", "data").isEmpty(), "битый ответ — без объявлений")
    }
}
