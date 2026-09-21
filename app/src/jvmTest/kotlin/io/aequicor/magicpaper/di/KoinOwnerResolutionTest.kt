package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.storage.InMemoryDurableByteStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.persistenceStores
import io.aequicor.magicpaper.domain.ChatRepository
import io.aequicor.magicpaper.domain.ChatJournalStore
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.ui.ChatComponent
import io.aequicor.magicpaper.ui.ChatService
import io.aequicor.magicpaper.ui.DefaultChatComponentFactory
import io.aequicor.magicpaper.ui.DefaultChatService
import io.aequicor.magicpaper.ui.DefaultSettingsComponentFactory
import io.aequicor.magicpaper.ui.SettingsComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.core.component.inject
import org.koin.test.KoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** KoinTest injection must use each runtime's isolated graph, including its qualified factories. */
@OptIn(ExperimentalCoroutinesApi::class)
class KoinOwnerResolutionTest {
    private class Owner : KoinTest {
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()),
            object : ProfileBridge {
                override val supportsFilePicker = false
                override suspend fun export(json: String) = false
                override suspend fun import(): String? = null
            }, NavigationSessionConfig())
        override fun getKoin(): Koin = runtime.koin
        val chat: ChatService by inject()
        val history: ChatRepository by inject()
        val chatFactory: ChatComponent.Factory by inject(FeatureFactoryQualifiers.chat)
        val settingsFactory: SettingsComponent.Factory by inject(FeatureFactoryQualifiers.settings)

        suspend fun createSavedSession(): String {
            chat.newSession()
            val id = requireNotNull(chat.state.value.current).id
            // The UI preview precedes durability; the production storage dispatcher is independent of the test scheduler.
            getKoin().get<ChatJournalStore>().states.first { it[id]?.sessions?.containsKey(id) == true }
            return id
        }
    }

    @Test fun qualifiedFactoriesAndServicesStayWithTheirOwnerAfterAnotherRuntimeCloses() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val first = Owner()
        val second = Owner()
        try {
            for (owner in listOf(first, second)) {
                owner.runtime.start()
                assertEquals(RuntimeState.Ready, owner.runtime.ready.first { it != RuntimeState.Loading })
                assertSame(owner.chat, owner.getKoin().get<DefaultChatService>())
                assertIs<DefaultChatComponentFactory>(owner.chatFactory)
                assertIs<DefaultSettingsComponentFactory>(owner.settingsFactory)
            }
            assertNotSame(first.getKoin(), second.getKoin())
            assertNotSame(first.chat, second.chat)
            val firstId = first.createSavedSession()
            assertEquals(listOf(firstId), first.history.sessions().map { it.id })
            assertTrue(second.history.sessions().isEmpty())

            first.runtime.close()
            first.runtime.awaitClosed()
            val secondId = second.createSavedSession()
            assertEquals(RuntimeState.Ready, second.runtime.ready.value)
            assertEquals(listOf(secondId), second.history.sessions().map { it.id })
            assertNotEquals(firstId, secondId)
        } finally {
            first.runtime.close(); first.runtime.awaitClosed()
            second.runtime.close(); second.runtime.awaitClosed()
            Dispatchers.resetMain()
        }
    }
}
