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

    /** Шлюз, у которого первый вызов падает, остальные отвечают заготовкой. */
    private class FlakyFirstGateway(private val reply: String) : LlmGateway {
        val tried = mutableListOf<String>()
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            tried += profile.name
            if (tried.size == 1) error("сервер молчит")
            return reply
        }
    }

    private val graphReply = """
        [
          {"title": "Каркас", "description": "d1", "agent": "Кодер", "model": "m-coder", "depends": []},
          {"title": "Тесты", "description": "d2", "agent": "Кодер", "model": "m-coder", "depends_on": [1]},
          {"title": "Доки", "description": "d3", "depends": [1, 9, 3, 1]}
        ]
    """.trimIndent()

    @Test
    fun graphStepsKeepModelAndDepends() = runTest {
        val composer = PlanComposer(ScriptedGateway(graphReply))
        val coder = LlmProfile(
            id = "p-coder", name = "Кодер", baseUrl = "http://x/v1", modelId = "m",
            favoriteModels = listOf("m-coder"),
        )
        val draft = composer.compose(
            "цель",
            coder,
            dossiers = emptyList(),
            candidates = listOf(coder, LlmProfile(id = "p-writer", name = "Писатель", baseUrl = "http://x/v1", modelId = "m")),
        )
        assertEquals(3, draft.milestones.size)
        // Параллельная ветвь: без зависимостей.
        assertEquals(emptyList(), draft.milestones[0].depends)
        // Оба написания ключа зависимостей работают.
        assertEquals(listOf(1), draft.milestones[1].depends)
        assertEquals("m-coder", draft.milestones[1].model)
        // Нумерация с 1: некорректные (в будущее, на себя, повторы) вычищены.
        assertEquals(listOf(1), draft.milestones[2].depends)
    }

    @Test
    fun secondPlannerRescuesWhenFirstFails() = runTest {
        val gateway = FlakyFirstGateway(graphReply)
        val composer = PlanComposer(gateway)
        val brokenJudge = LlmProfile(id = "judge", name = "Судья", baseUrl = "http://x/v1", modelId = "m")
        val draft = composer.compose("цель", brokenJudge, dossiers = emptyList(), candidates = candidates)
        // Первый планировщик сгорел — compose сам перешёл на настроенного кандидата.
        assertEquals(2, gateway.tried.size)
        assertEquals(3, draft.milestones.size)
        assertTrue(draft.note.isBlank())
    }

    @Test
    fun singleStepOnBigGoalRejected() = runTest {
        val gateway = ScriptedGateway("""[{"title": "Всё сразу", "description": "d"}]""")
        val composer = PlanComposer(gateway)
        val bigGoal = "добавить полноценную авторизацию с тестами, документацией и миграциями базы данных"
        val draft = composer.compose(bigGoal, candidates.first(), dossiers = emptyList(), candidates = candidates)
        // Модель отдалась одной вехой на объёмную цель — это фолбэк с честной причиной, а не «оптимальный план».
        assertEquals(1, draft.milestones.size)
        assertTrue(draft.note.contains("без модели"))
        assertTrue(draft.note.contains("один шаг"))
    }

    @Test
    fun wrapperObjectReplyParsed() = runTest {
        val reply = "План такой:\n```json\n{\"milestones\": $graphReply}\n```\nУдачи!"
        val composer = PlanComposer(ScriptedGateway(reply))
        val draft = composer.compose("цель", candidates.first(), dossiers = emptyList(), candidates = candidates)
        assertEquals(3, draft.milestones.size)
        assertEquals("Каркас", draft.milestones[0].title)
    }
}
