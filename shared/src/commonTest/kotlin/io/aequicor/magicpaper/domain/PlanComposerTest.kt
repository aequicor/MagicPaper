package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanComposerTest {

    /** Шлюз без модели: любой вызов падает, как не подключённый сервер. */
    private class FailingGateway : LlmGateway {
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String =
            throw IllegalStateException("нет модели")
    }

    /** Шлюз, возвращающий готовый JSON-план (возможно обёрнутый в ```). */
    private class ScriptedGateway(private val reply: String) : LlmGateway {
        var lastMessages: List<LlmMessage> = emptyList()
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            lastMessages = messages
            return reply
        }
    }

    private val candidates = listOf(
        LlmProfile(id = "p-coder", name = "Кодер", baseUrl = "http://x/v1", modelId = "m"),
        LlmProfile(id = "p-writer", name = "Писатель", baseUrl = "http://x/v1", modelId = "m"),
    )

    @Test
    fun heuristicDraftWithoutModel() = runTest {
        val composer = PlanComposer(FailingGateway())
        val draft = composer.compose("сделать авторизацию", profile = null, dossiers = emptyList(), candidates = candidates)
        assertEquals(1, draft.milestones.size)
        assertTrue(draft.note.isNotBlank())
    }

    @Test
    fun modelPlanParsedAndAgentsResolved() = runTest {
        val gateway = ScriptedGateway(
            """
            Вот план:
            ```json
            [
              {"title": "Схема БД", "description": "создать таблицы", "agent": "Кодер"},
              {"title": "Документация", "description": "описать API", "agent": "Писатель"},
              {"title": "Неизвестный шаг", "description": "что-то", "agent": "кто-то странный"}
            ]
            ```
            """.trimIndent()
        )
        val composer = PlanComposer(gateway)
        val planner = LlmProfile(id = "planner", name = "Планер", baseUrl = "http://x/v1", modelId = "m")
        val draft = composer.compose("цель", planner, dossiers = emptyList(), candidates = candidates)

        assertEquals(3, draft.milestones.size)
        assertEquals("Схема БД", draft.milestones[0].title)
        // Имена агентов из ответа модели сопоставлены с реальными профилями.
        assertEquals("Кодер", draft.milestones[0].agent)
        assertEquals("Писатель", draft.milestones[1].agent)
        // Несовпавший агент — пустая строка (подберёт матчер при утверждении).
        assertEquals("", draft.milestones[2].agent)
        // Досье (список агентов) попало в контекст модели.
        assertTrue(gateway.lastMessages.any { it.content.contains("Доступные агенты") })
    }

    @Test
    fun modelFailureFallsBackToHeuristic() = runTest {
        val composer = PlanComposer(FailingGateway())
        val planner = LlmProfile(id = "planner", name = "Планер", baseUrl = "http://x/v1", modelId = "m")
        val draft = composer.compose("цель", planner, dossiers = emptyList(), candidates = candidates)
        assertTrue(draft.note.contains("без модели"))
    }
}
