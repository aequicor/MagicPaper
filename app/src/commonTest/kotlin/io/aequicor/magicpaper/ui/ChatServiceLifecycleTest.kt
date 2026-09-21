package io.aequicor.magicpaper.ui

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import io.aequicor.magicpaper.data.docs.EmbeddedDocRepository
import io.aequicor.magicpaper.data.storage.NoopFilePicker
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceLifecycleTest {
    private suspend fun service(f: ModelSettingsFixture, repository: ChatCheckpointStore = f.chats, gateway: LlmGateway = f.gateway): DefaultChatService {
        f.seed()
        return DefaultChatService(testGatewayRuntime(gateway, f.search, EmbeddedDocRepository()), f.newChatStore(repository),
            f.settings, f.profiles, null, workerDispatcher = Dispatchers.Main,
            draftRepository = f.draftRepository, draftBlobs = f.draftBlobs).also { it.start(); it.activate("first") }
    }

    @Test fun newChatIsStoredBeforeItsFirstMessageAndFastRepliesReleaseTheSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val created = CompletableDeferred<Unit>()
        f.beforeChatInput = { if (it is ChatMachine.Intent.CreateNotebook) created.await() }
        val service = service(f)
        try {
            service.activate(null)
            service.composerDraft(null).update(ComposerDraftData("Первое сообщение"))
            runCurrent()
            service.send("Первое сообщение")
            runCurrent()
            assertTrue(f.calls.isEmpty())
            assertTrue(service.state.value.current!!.messages.isEmpty(), "No message is accepted before notebook durability")
            created.complete(Unit)
            advanceUntilIdle()
            val id = assertNotNull(service.state.value.current).id
            assertEquals(2, f.chats.session(id)?.messages?.size)
            assertFalse(service.state.value.busy)
            assertEquals("", service.composerDraft(null).state.value.value.text)
            service.send("Второе сообщение")
            advanceUntilIdle()
            assertEquals(4, f.chats.session(id)?.messages?.size)
            assertEquals(2, f.calls.size)
        } finally { created.complete(Unit); service.close(); Dispatchers.resetMain() }
    }

    @Test fun failedMessageWriteKeepsDraftAndNeverCallsTheProvider() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        f.beforeChatInput = { if (it is ChatMachine.Intent.Submit) error("Disk full") }
        val service = service(f)
        try {
            service.composerDraft("first").update(ComposerDraftData("Не терять"))
            runCurrent()
            service.send("Не терять")
            advanceUntilIdle()
            assertTrue(f.calls.isEmpty())
            assertFalse(service.state.value.busy)
            assertEquals("Не терять", service.composerDraft("first").state.value.value.text)
            assertNotNull(service.state.value.notice)
        } finally { service.close(); Dispatchers.resetMain() }
    }

    @Test fun leavingComponentDoesNotStopResponseAndLaterDraftSurvivesAcceptedMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ModelSettingsFixture()
        val save = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Unit>()
        f.beforeChatInput = { if (it is ChatMachine.Intent.Submit) save.await() }
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                answer.await(); return "Ответ"
            }
        }
        val service = service(f, gateway = gateway)
        val lifecycle = LifecycleRegistry().apply { create(); resume() }
        try {
            DefaultChatComponent(DefaultComponentContext(lifecycle), service, ChatInput("first"), NoopFilePicker) {}
            val draft = service.composerDraft("first")
            draft.update(ComposerDraftData("Отправить")); runCurrent()
            service.send("Отправить"); runCurrent()
            draft.update(ComposerDraftData("Следующий черновик"))
            lifecycle.destroy()
            save.complete(Unit); runCurrent()
            assertEquals("Следующий черновик", draft.state.value.value.text)
            answer.complete(Unit); advanceUntilIdle()
            assertEquals("Ответ", f.chats.session("first")?.messages?.last()?.text)
        } finally { save.complete(Unit); answer.complete(Unit); service.close(); Dispatchers.resetMain() }
    }
}
