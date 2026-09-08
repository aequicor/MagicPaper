package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonCodingProjectRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = InMemoryKeyValueStore()
    private val repo = JsonCodingProjectRepository(store, json)

    private fun user(id: String, text: String) =
        CodingMessage(id = id, role = CodingRole.USER, text = text, createdAt = 1L)

    private fun agent(id: String, text: String) =
        CodingMessage(id = id, role = CodingRole.AGENT, text = text, createdAt = 2L)

    @Test
    fun projectsRoundTripAndDelete() = runTest {
        val a = CodingProject(id = "a", name = "app", path = "/tmp/app", createdAt = 1L)
        val b = CodingProject(id = "b", name = "lib", path = "/tmp/lib", createdAt = 2L, piSessionId = "s-1")
        repo.save(a)
        repo.save(b)

        val loaded = repo.all()
        assertEquals(listOf(b, a), loaded) // новые выше

        repo.save(a.copy(name = "app2"))
        assertEquals("app2", repo.all().first { it.id == "a" }.name)

        repo.delete("a")
        assertEquals(listOf(b), repo.all())
    }

    @Test
    fun newProjectGetsMainSessionOnFirstAccess() = runTest {
        repo.save(CodingProject(id = "p", name = "p", path = "/tmp/p", createdAt = 5L))
        val sessions = repo.sessions("p")
        assertEquals(1, sessions.size)
        assertEquals("main-p", sessions[0].id)
        assertEquals("Основная", sessions[0].name)
        assertEquals(5L, sessions[0].createdAt)
    }

    @Test
    fun sessionLogsAreIndependent() = runTest {
        val project = CodingProject(id = "p", name = "p", path = "/tmp/p", createdAt = 1L)
        repo.save(project)
        val first = repo.sessions("p").first()
        val second = CodingSession(id = "s2", projectId = "p", name = "Сессия 2", createdAt = 2L)
        repo.saveSession(second)

        repo.saveMessages("p", first.id, listOf(user("m1", "привет"), agent("m2", "готово")))
        repo.saveMessages("p", second.id, listOf(user("m3", "другая нить")))

        assertEquals(2, repo.sessions("p").size)
        assertEquals(2, repo.messages("p", first.id).size)
        assertEquals(1, repo.messages("p", second.id).size)
        assertEquals("другая нить", repo.messages("p", second.id).first().text)
    }

    @Test
    fun deleteSessionClearsOnlyItsLog() = runTest {
        val project = CodingProject(id = "p", name = "p", path = "/tmp/p", createdAt = 1L)
        repo.save(project)
        val first = repo.sessions("p").first()
        repo.saveSession(CodingSession(id = "s2", projectId = "p", name = "2", createdAt = 2L))
        repo.saveMessages("p", first.id, listOf(user("m1", "a")))
        repo.saveMessages("p", "s2", listOf(user("m2", "b")))

        repo.deleteSession("p", "s2")
        assertEquals(listOf(first.id), repo.sessions("p").map { it.id })
        assertTrue(repo.messages("p", "s2").isEmpty())
        assertEquals(1, repo.messages("p", first.id).size)
    }

    @Test
    fun legacyProjectMigratesLogAndSessionId() = runTest {
        // Данные «до сессий»: журнал под старым ключом и piSessionId на проекте.
        val project = CodingProject(id = "old", name = "old", path = "/tmp/old", createdAt = 1L, piSessionId = "pi-7")
        repo.save(project)
        store.write("coding-log:old", json.encodeToString(ListSerializer(CodingMessage.serializer()), listOf(user("m", "старый журнал"))))

        val sessions = repo.sessions("old")
        assertEquals(1, sessions.size)
        assertEquals("pi-7", sessions.first().piSessionId) // контекст переехал в сессию
        assertEquals("старый журнал", repo.messages("old", sessions.first().id).single().text)

        // Старый ключ освобождён: повторное обращение не дублирует историю.
        store.delete("coding-log:old")
        assertEquals(1, repo.messages("old", sessions.first().id).size)
    }

    @Test
    fun messagesRoundTripAndClearedWithProject() = runTest {
        val project = CodingProject(id = "p", name = "p", path = "/tmp/p", createdAt = 1L)
        repo.save(project)
        val session = repo.sessions("p").first()
        val messages = listOf(
            user("m1", "привет"),
            CodingMessage(id = "m2", role = CodingRole.AGENT, text = "готово", activity = listOf("⚒ read"), createdAt = 2L),
        )
        repo.saveMessages("p", session.id, messages)
        assertEquals(messages, repo.messages("p", session.id))

        repo.delete("p")
        assertTrue(repo.messages("p", session.id).isEmpty())
        assertTrue(repo.sessions("p").isEmpty())
    }

    @Test
    fun wipeClearsEverything() = runTest {
        repo.save(CodingProject(id = "x", name = "x", path = "/tmp/x", createdAt = 1L))
        val session = repo.sessions("x").first()
        repo.saveMessages("x", session.id, listOf(user("m", "t")))
        repo.wipe()
        assertTrue(repo.all().isEmpty())
        assertTrue(repo.sessions("x").isEmpty())
    }

    @Test
    fun corruptedDataFallsBackToEmpty() = runTest {
        store.write("coding-projects", "{broken")
        assertTrue(repo.all().isEmpty())
        store.write("coding-sessions", "{broken")
        assertTrue(repo.sessions("anything").isEmpty())
        store.write("coding-log:q:z", "{broken")
        assertTrue(repo.messages("q", "z").isEmpty())
    }
    @Test fun orchestrationRoundTripsAndRecoversLastSnapshot() = runTest {
        val first = OrchestrationState("parent", "p", inputs = listOf(OrchestrationInput("input", "Вопрос", 1)),
            stageNumbers = mapOf("plan:stage" to 3), nextStageNumber = 4)
        repo.saveOrchestration(first)
        val second = first.copy(inputs = first.inputs.map { it.copy(status = OrchestrationInputStatus.DONE) })
        repo.saveOrchestration(second)
        assertEquals(second, JsonCodingProjectRepository(store, json).orchestration("parent"))
        store.write("coding-orchestration-parent", "{broken")
        assertEquals(first, repo.orchestration("parent"))
        store.delete("coding-orchestration-parent")
        assertEquals(first, repo.orchestration("parent"))
    }

    @Test fun corruptedOrchestrationWithoutBackupIsAnError() = runTest {
        store.write("coding-orchestration-parent", "{broken")
        assertFails { repo.orchestration("parent") }
        assertNull(repo.orchestration("missing"))
    }

    @Test fun deletingASessionRemovesItsOrchestrationSnapshotsOnly() = runTest {
        repo.save(CodingProject("p", "Project", "/p", 1))
        repo.saveSession(CodingSession("parent", "p", "Root", 1))
        repo.saveSession(CodingSession("other", "p", "Other", 2))
        repo.saveOrchestration(OrchestrationState("parent", "p"))
        repo.saveOrchestration(OrchestrationState("parent", "p", nextStageNumber = 2))
        val other = OrchestrationState("other", "p")
        repo.saveOrchestration(other)
        repo.deleteSession("p", "parent")
        assertNull(repo.orchestration("parent"))
        assertEquals(other, repo.orchestration("other"))
    }

}
