package io.aequicor.magicpaper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import io.aequicor.magicpaper.data.coding.BackgroundCodingProjectRepository
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentUiThreadTest {
    @Test fun blockingAgentAndStorageLeaveTheRealDesktopEventThreadResponsive() = runBlocking {
        val ui = Executor { EventQueue.invokeLater(it) }.asCoroutineDispatcher()
        Dispatchers.setMain(ui)
        val storageEntered = CountDownLatch(1)
        val storageRelease = CountDownLatch(1)
        val agentEntered = CountDownLatch(1)
        val agentRelease = CountDownLatch(1)
        var vm: MagicPaperViewModel? = null
        var scene: ImageComposeScene? = null
        try {
            val fixture = ModelSettingsFixture()
            val pauseRead = AtomicBoolean(false)
            val base = JsonCodingProjectRepository(fixture.kv, fixture.json)
            val repository = BackgroundCodingProjectRepository(object : CodingProjectRepository by base {
                override suspend fun messages(projectId: String, sessionId: String): List<CodingMessage> {
                    assertFalse(EventQueue.isDispatchThread(), "Repository decoding inherited the UI thread")
                    if (pauseRead.compareAndSet(true, false)) {
                        storageEntered.countDown()
                        check(storageRelease.await(5, TimeUnit.SECONDS))
                    }
                    return base.messages(projectId, sessionId)
                }
            }, historyCacheSize = 0) // Exercise actual blocked storage, bypassing the normal history cache.
            val project = CodingProject("p", "Project", "/fake", 0)
            val session = CodingSession("s", "p", "Session", 0, engine = CodingEngine.CODEX)
            repository.save(project); repository.saveSession(session)
            val runtime = object : CodingRuntime {
                override val supported = true
                override val rootPath = "/fake"
                override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
                override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
                override suspend fun uninstall() = Unit
                override fun abort(sessionId: String) = Unit
                override fun abortAll() = Unit
                override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                    assertFalse(EventQueue.isDispatchThread(), "Agent execution inherited the UI thread")
                    emit(CodingEvent.SessionStarted("native"))
                    agentEntered.countDown()
                    check(agentRelease.await(5, TimeUnit.SECONDS))
                    repeat(10_000) { emit(CodingEvent.TextDelta("$it|")) }
                    emit(CodingEvent.Finished)
                }
            }
            val model = withContext(ui) { fixture.prepare(runtime, repository, workerDispatcher = Dispatchers.Default) }
            vm = model
            withTimeout(5000) { model.state.first { it.coding.sessions.isNotEmpty() } }
            val clicks = mutableIntStateOf(0)
            val window = withContext(ui) {
                ImageComposeScene(400, 160) { MagicPaperTheme { Column {
                    TextButton(onClick = { clicks.intValue++ }) { Text("Проверка") }
                } } }.also { it.render(0).close() }
            }
            scene = window
            suspend fun click() = withTimeout(1500) {
                withContext(ui) {
                    window.sendPointerEvent(PointerEventType.Press, Offset(40f, 20f))
                    window.sendPointerEvent(PointerEventType.Release, Offset(40f, 20f))
                    window.render(System.nanoTime()).close()
                }
            }
            pauseRead.set(true)
            withContext(ui) { model.sendCodingPromptTo("s", "Start") }
            assertTrue(storageEntered.await(5, TimeUnit.SECONDS))
            click()
            assertEquals(1, clicks.intValue, "Buttons must respond while storage is occupied")
            storageRelease.countDown()
            assertTrue(agentEntered.await(5, TimeUnit.SECONDS))
            click()
            assertEquals(2, clicks.intValue, "Buttons must respond while the agent is occupied")
            agentRelease.countDown()
            val completed = withTimeout(10_000) { model.state.first { state ->
                state.coding.sessions.any { !it.running && it.messages.any { message -> message.role == CodingRole.AGENT } }
            } }
            val answer = completed.coding.sessions.single().messages.last()
            assertFalse(answer.failed)
            assertEquals((0 until 10_000).joinToString("") { "$it|" }, answer.text)
        } finally {
            storageRelease.countDown(); agentRelease.countDown()
            withContext(ui) { scene?.close(); vm?.shutdownCoding() }
            Dispatchers.resetMain()
        }
    }
}
