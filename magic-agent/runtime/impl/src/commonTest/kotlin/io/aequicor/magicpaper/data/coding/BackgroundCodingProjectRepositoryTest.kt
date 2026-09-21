package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class BackgroundCodingProjectRepositoryTest {
    private fun history(text: String) = listOf(CodingMessage(text, CodingRole.AGENT, text, createdAt = 1))

    @Test fun repeatedHistoryRefreshesReadTheAuthorityWithoutReloadingCheckpoint() = runTest {
        val kv = InMemoryKeyValueStore()
        val durable = JsonCodingProjectRepository(kv, Json)
        durable.save(CodingProject("p", "Project", "/project", 1))
        durable.saveSession(CodingSession("s", "p", "Session", 1, engine = CodingEngine.CODEX))
        durable.saveMessages("p", "s", history("old"))
        var imports = 0
        val background = BackgroundCodingProjectRepository(object : CodingCheckpointStore by durable {
            override suspend fun legacyProjects(excludingIds: Set<String>): List<CodingMachine.Fact.LegacyImported> {
                imports++; return durable.legacyProjects(excludingIds)
            }
        }, StandardTestDispatcher(testScheduler))
        val owner = journalCodingProjects(kv, checkpoints = background)
        val first = owner.messages("p", "s")
        repeat(1000) { assertSame(first, owner.messages("p", "s")) }
        assertEquals(1, imports)
        owner.publishTestHistory("p", "s", history("new"))
        assertEquals(history("old") + history("new"), owner.messages("p", "s"))
        assertEquals(owner.messages("p", "s"), durable.messages("p", "s"))
        owner.dispatch("p", CodingMachine.Intent.DeleteSession(CodingMachine.SessionRef("s", 0)))
        assertTrue(owner.messages("p", "s").isEmpty())
        owner.wipe()
        assertTrue(owner.all().isEmpty())
    }

    @Test fun failedCompatibilityCheckpointCannotHideDurableHistoryAfterReopen() = runTest {
        val kv = InMemoryKeyValueStore(); val events = InMemoryEventJournal()
        val durable = JsonCodingProjectRepository(kv, Json)
        var failAfterCommit = false
        val background = BackgroundCodingProjectRepository(object : CodingCheckpointStore by durable {
            override suspend fun checkpoint(state: CodingMachine.State) {
                durable.checkpoint(state)
                if (failAfterCommit) error("Write acknowledgement lost")
            }
        }, StandardTestDispatcher(testScheduler))
        val owner = journalCodingProjects(kv, journal = events, checkpoints = background)
        owner.createTestProject(CodingProject("p", "Project", "/project", 1))
        owner.createTestSession(CodingSession("s", "p", "Session", 1, engine = CodingEngine.PI))
        failAfterCommit = true
        owner.publishTestHistory("p", "s", history("committed"))
        assertEquals(history("committed"), owner.messages("p", "s"))
        assertNotNull(owner.failures.value["p"])
        val reopened = journalCodingProjects(kv, journal = events)
        assertEquals(history("committed"), reopened.messages("p", "s"))
        assertTrue(reopened.failures.value.isEmpty())
    }
}
