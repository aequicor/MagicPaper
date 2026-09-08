package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class BackgroundCodingProjectRepositoryTest {
    private fun history(text: String) = listOf(CodingMessage(text, CodingRole.AGENT, text, createdAt = 1))

    @Test fun repeatedHistoryRefreshesReuseTheSavedListAndWritesStayVisible() = runTest {
        val durable = JsonCodingProjectRepository(InMemoryKeyValueStore(), Json)
        durable.save(CodingProject("p", "Project", "/project", 1))
        durable.saveSession(CodingSession("s", "p", "Session", 1, engine = CodingEngine.CODEX))
        durable.saveMessages("p", "s", history("old"))
        var reads = 0
        val repo = BackgroundCodingProjectRepository(object : CodingProjectRepository by durable {
            override suspend fun messages(projectId: String, sessionId: String): List<CodingMessage> {
                reads++; return durable.messages(projectId, sessionId)
            }
        }, StandardTestDispatcher(testScheduler))
        val first = repo.messages("p", "s")
        repeat(1000) { assertSame(first, repo.messages("p", "s")) }
        assertEquals(1, reads)
        val next = history("new")
        repo.saveMessages("p", "s", next)
        assertSame(next, repo.messages("p", "s"))
        assertEquals(next, durable.messages("p", "s"))
        repo.deleteSession("p", "s")
        assertTrue(repo.messages("p", "s").isEmpty())
        repo.saveSession(CodingSession("s", "p", "Session", 1, engine = CodingEngine.CODEX))
        repo.saveMessages("p", "s", next)
        repo.wipe()
        assertTrue(repo.messages("p", "s").isEmpty())
    }

    @Test fun cacheIsBoundedAndAnUncertainWriteInvalidatesTheOldHistory() = runTest {
        val durable = JsonCodingProjectRepository(InMemoryKeyValueStore(), Json)
        var reads = 0
        var failAfterCommit = false
        val repo = BackgroundCodingProjectRepository(object : CodingProjectRepository by durable {
            override suspend fun messages(projectId: String, sessionId: String): List<CodingMessage> {
                reads++; return durable.messages(projectId, sessionId)
            }
            override suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>) {
                durable.saveMessages(projectId, sessionId, messages)
                if (failAfterCommit) error("Write acknowledgement lost")
            }
        }, StandardTestDispatcher(testScheduler), historyCacheSize = 2)
        for (id in listOf("a", "b", "c")) repo.saveMessages("p", id, history(id))
        repo.messages("p", "b"); repo.messages("p", "c")
        assertEquals(0, reads)
        assertEquals(history("a"), repo.messages("p", "a"))
        assertEquals(1, reads, "Only the least recently used history should be evicted")
        failAfterCommit = true
        assertFailsWith<IllegalStateException> { repo.saveMessages("p", "a", history("committed")) }
        assertEquals(history("committed"), repo.messages("p", "a"))
        assertEquals(2, reads)
        repo.delete("p")
        // delete invalidates even a cached orphan log not listed as a project session.
        repo.messages("p", "a")
        assertEquals(3, reads)
    }
}
