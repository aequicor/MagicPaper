package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.InMemorySecretStore
import io.aequicor.magicpaper.data.storage.JsonLlmProfileRepository
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Название задачи даёт модель по полному первому запросу. Проверки закрепляют, что модель
 * получает текст запроса, а не обрезанное название сессии, и что вызов одиночный.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTitleServiceTest {

    private val profile = LlmProfile("model", "Модель", baseUrl = "http://example.test/v1", modelId = "m")
    private val project = CodingProject("project", "Project", "/work", 1)

    private inner class Fixture(answer: suspend (List<LlmMessage>) -> String) {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val kv = InMemoryKeyValueStore()
        val projects = JsonCodingProjectRepository(kv, json)
        val profiles = JsonLlmProfileRepository(kv, json, InMemorySecretStore())
        val settings = JsonSettingsRepository(kv, json, InMemorySecretStore())
        val calls = mutableListOf<List<LlmMessage>>()
        private val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                calls += messages.toList()
                return answer(messages)
            }
        }
        private val scope = TestScope()
        private val service = SessionTitleService(projects, profiles, settings, gateway, scope)

        init { runBlocking { profiles.save(profile) } }

        /** Prepares stored state synchronously, then lets every title job finish. */
        fun sync(session: CodingSession, vararg messages: CodingMessage, saved: Boolean = true) {
            runBlocking {
                projects.save(project)
                projects.saveSession(session)
                if (saved) projects.saveMessages(project.id, session.id, messages.toList())
            }
            service.sync(session)
            scope.advanceUntilIdle()
        }

        fun stored(): CodingSession = runBlocking { projects.sessions(project.id).single() }
    }

    @Test fun titlesEveryStartedRootIncludingPlainCodingSessions() {
        val f = Fixture { "Исправить автонейминг" }
        f.sync(CodingSession("root", project.id, "Новая сессия", 1),
            user("автонейминг создаёт названия сессий, не закрывающие выполняемую задачу"))
        assertEquals("Исправить автонейминг", f.stored().shortTitle)
    }

    @Test fun modelSeesTheWholeRequestAndNotTheTruncatedName() {
        val f = Fixture { "Назвать задачу" }
        val long = "а".repeat(200) + " — починить загрузку проекта"
        f.sync(CodingSession("root", project.id, "🗓️ " + long.take(20), 1), user(long))
        val prompt = f.calls.single().last().content
        assertTrue(prompt.contains("починить загрузку проекта"), "The whole request reaches the model")
        assertEquals("Назвать задачу", f.stored().shortTitle)
    }

    @Test fun liveRunPromptNamesTheSessionBeforeTheJournalIsWritten() {
        val f = Fixture { "Падение сборки" }
        val session = CodingSession("root", project.id, "Новая сессия", 1,
            pendingRun = CodingRunCheckpoint("m1", "почему падает сборка десктопа"))
        f.sync(session, saved = false)
        assertEquals("Падение сборки", f.stored().shortTitle)
    }

    @Test fun manualWorkersReadyTitlesImmunityAndArchivedAreNeverCalled() {
        val f = Fixture { "Не должно появиться" }
        val started = CodingSession("root", project.id, "Новая сессия", 1)
        listOf(
            started.copy(nameManuallySet = true),
            started.copy(parentSessionId = "parent"),
            started.copy(shortTitle = "Готово"),
            started.copy(sessionKind = SessionKind.IMMUNITY),
            started.copy(archived = true),
        ).forEach { f.sync(it, user("задача")) }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun modelFailureLeavesTheNameAloneAndIsRetriedOnlyFewTimes() {
        val f = Fixture { error("модель недоступна") }
        val session = CodingSession("root", project.id, "Новая сессия", 1)
        repeat(5) { f.sync(session, user("задача")) }
        assertTrue(f.calls.size in 1..3, "A broken model is retried a bounded number of times (was ${f.calls.size})")
        assertEquals("", f.stored().shortTitle)
        assertEquals("Новая сессия", f.stored().name)
    }

    @Test fun missingRequestPostponesTheTitleInsteadOfLosingIt() {
        val f = Fixture { "Поиск по файлам" }
        val session = CodingSession("root", project.id, "Новая сессия", 1)
        f.sync(session, saved = false)
        assertTrue(f.calls.isEmpty(), "Пока запрос не сохранён, модель не вызывается")
        f.sync(session, user("сделай поиск по файлам в проекте"))
        assertEquals("Поиск по файлам", f.stored().shortTitle)
    }

    @Test fun unusableModelAnswerKeepsThePreviousName() {
        val f = Fixture { "—" }
        val session = CodingSession("root", project.id, "Новая сессия", 1)
        f.sync(session, user("задача"))
        assertEquals(1, f.calls.size)
        assertEquals("", f.stored().shortTitle)
        assertEquals("Новая сессия", f.stored().name)
    }

    @Test fun readyTitleIsPublishedOnceAndSurvivesTheNextRefresh() {
        val f = Fixture { "Загрузка проекта" }
        f.sync(CodingSession("root", project.id, "Новая сессия", 1), user("почини загрузку проекта"))
        assertEquals("🗓️ Загрузка проекта", f.stored().sidebarTitle())
        f.sync(f.stored(), saved = false)
        assertEquals(1, f.calls.size, "A titled session is never summarised twice")
    }

    private fun user(text: String) = CodingMessage("m1", CodingRole.USER, text, createdAt = 1)
}
