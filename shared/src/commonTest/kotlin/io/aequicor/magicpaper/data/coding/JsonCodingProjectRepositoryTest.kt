package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonCodingProjectRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = InMemoryKeyValueStore()
    private val repo = JsonCodingProjectRepository(store, json)

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
    fun messagesRoundTripAndClearedWithProject() = runTest {
        val project = CodingProject(id = "p", name = "p", path = "/tmp/p", createdAt = 1L)
        repo.save(project)
        val messages = listOf(
            CodingMessage(id = "m1", role = CodingRole.USER, text = "привет", createdAt = 1L),
            CodingMessage(id = "m2", role = CodingRole.AGENT, text = "готово", activity = listOf("⚒ read"), createdAt = 2L),
        )
        repo.saveMessages("p", messages)
        assertEquals(messages, repo.messages("p"))

        repo.delete("p")
        assertTrue(repo.messages("p").isEmpty())
    }

    @Test
    fun wipeClearsEverything() = runTest {
        repo.save(CodingProject(id = "x", name = "x", path = "/tmp/x", createdAt = 1L))
        repo.saveMessages("x", listOf(CodingMessage(id = "m", role = CodingRole.USER, text = "t", createdAt = 1L)))
        repo.wipe()
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun corruptedDataFallsBackToEmpty() = runTest {
        store.write("coding-projects", "{broken")
        assertTrue(repo.all().isEmpty())
        store.write("coding-log:q", "{broken")
        assertTrue(repo.messages("q").isEmpty())
    }
}
