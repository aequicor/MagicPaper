package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatLayoutWorkflowTest {
    @Test fun acceptedRequestCapturesProjectAndRestoreNeverLaunchesEditor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ModelSettingsFixture()
        fixture.seed()
        val first = CodingProject("p1", "First", "/first", 0)
        val second = CodingProject("p2", "Second", "/second", 0)
        var selected = first
        val opens = mutableListOf<String>()
        val published = mutableListOf<String>()
        val editor = object : LayoutEditor {
            override suspend fun open(project: CodingProject, conversationId: String): LayoutWorkspace {
                opens += project.id; return LayoutWorkspace(project.id, project.path, "old", "catalog")
            }
            override suspend fun render(workspace: LayoutWorkspace, source: String) =
                LayoutRender(true, "", Attachment.fromBytes("layout.png", "image/png", byteArrayOf(1, 2)))
            override suspend fun publish(workspace: LayoutWorkspace, source: String) { published += workspace.projectId }
        }
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = "layout"
        }
        fun service() = DefaultChatService(GatewaySessionRuntime(gateway, fixture.search, EmbeddedDocRepository(), layoutEditor = editor),
            fixture.chats, fixture.settings, fixture.profiles, null, workerDispatcher = Dispatchers.Main,
            layoutAgent = LayoutChatAgent(gateway, editor),
            layoutProject = { id -> if (id == null) selected else listOf(first, second).find { it.id == id } })
        var chat = service()
        try {
            chat.start(); chat.activate("first")
            chat.send("Создай макет экрана входа")
            selected = second
            advanceUntilIdle()
            assertEquals(listOf("p1"), opens)
            assertEquals(listOf("p1"), published)
            val saved = assertNotNull(fixture.chats.session("first"))
            assertEquals("p1", saved.layoutProjectId)
            assertEquals(1, saved.messages.last().attachments.size)
            chat.close()
            chat = service(); chat.start(); chat.activate("first"); advanceUntilIdle()
            assertEquals(1, opens.size, "Restoring a chat must not reopen an editor")
            chat.send("Добавь кнопку слева")
            advanceUntilIdle()
            assertEquals(listOf("p1", "p1"), opens, "Follow-up stays bound after project selection changes")
        } finally { chat.close(); Dispatchers.resetMain() }
    }
}
