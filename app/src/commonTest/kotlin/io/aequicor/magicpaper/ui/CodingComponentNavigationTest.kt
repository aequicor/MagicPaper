package io.aequicor.magicpaper.ui

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.NoopFilePicker
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingComponentNavigationTest {
    @Test fun displayObservationsContinueAfterScreenDestructionAndClearWhenOwnerClears() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val lifecycle = LifecycleRegistry()
        var service: DefaultCodingService? = null
        try {
            val fixture = ModelSettingsFixture()
            val coding = fixture.prepareCoding(activate = false).also { service = it }
            val component = DefaultCodingComponentFactory(coding, NoopFilePicker)
                .create(DefaultComponentContext(lifecycle), CodingInput()) {}
            lifecycle.resume(); runCurrent()
            lifecycle.destroy(); runCurrent()
            val usage = ContextUsageSnapshot("coding:background", "model", used = 123, limit = 1000, updatedAt = 1)
            fixture.usage.context(usage); runCurrent()
            assertEquals(usage, coding.state.value.coding.usageContexts[usage.conversationId])
            assertEquals(usage, component.state.value.coding.usageContexts[usage.conversationId])
            fixture.usage.clear(); runCurrent()
            assertTrue(coding.state.value.coding.usageContexts.isEmpty())
            assertTrue(fixture.calls.isEmpty())
        } finally { lifecycle.destroy(); service?.close(); Dispatchers.resetMain() }
    }

    @Test fun sessionLinkMustBelongToItsProjectAndMissingProjectClearsSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val fixture = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(fixture.kv, fixture.json)
            repo.save(CodingProject("a", "A", "/a", 1))
            repo.save(CodingProject("b", "B", "/b", 1))
            repo.saveSession(CodingSession("a-session", "a", "A", 1))
            repo.saveSession(CodingSession("b-session", "b", "B", 1))
            val coding = fixture.prepareCoding(codingProjects = repo, activate = false).also { service = it }
            coding.activate("a", "b-session")
            assertEquals("a", coding.state.value.coding.current?.id)
            assertNull(coding.state.value.coding.currentSessionId)
            coding.activate("missing", "b-session")
            assertNull(coding.state.value.coding.current)
            assertNull(coding.state.value.coding.currentSessionId)
            assertEquals(2, repo.all().size)
            assertEquals(listOf("a-session"), repo.sessions("a").map { it.id })
            assertEquals(listOf("b-session"), repo.sessions("b").map { it.id })
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    @Test fun destroyedScreenCannotOverwriteTheNextSelectionAfterDelayedRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val first = LifecycleRegistry()
        val second = LifecycleRegistry()
        var service: DefaultCodingService? = null
        try {
            val fixture = ModelSettingsFixture()
            val saved = JsonCodingProjectRepository(fixture.kv, fixture.json)
            saved.save(CodingProject("a", "A", "/a", 1))
            saved.save(CodingProject("b", "B", "/b", 1))
            saved.saveSession(CodingSession("a-session", "a", "A", 1))
            saved.saveSession(CodingSession("b-session", "b", "B", 1))
            var delayFirst = false
            val repo = object : CodingProjectRepository by saved {
                override suspend fun messages(projectId: String, sessionId: String): List<CodingMessage> {
                    if (delayFirst && projectId == "a") withContext(NonCancellable) { gate.await() }
                    return saved.messages(projectId, sessionId)
                }
            }
            val coding = fixture.prepareCoding(codingProjects = repo, activate = false).also { service = it }
            val factory = DefaultCodingComponentFactory(coding, NoopFilePicker)
            delayFirst = true
            factory.create(DefaultComponentContext(first), CodingInput("a", "a-session")) {}
            first.resume(); runCurrent()
            first.destroy()
            factory.create(DefaultComponentContext(second), CodingInput("b", "b-session")) {}
            second.resume(); runCurrent()
            assertEquals("b-session", coding.state.value.coding.currentSessionId)
            gate.complete(Unit); runCurrent()
            assertEquals("b", coding.state.value.coding.current?.id)
            assertEquals("b-session", coding.state.value.coding.currentSessionId)
        } finally {
            gate.complete(Unit)
            first.destroy(); second.destroy()
            service?.close()
            Dispatchers.resetMain()
        }
    }
}
