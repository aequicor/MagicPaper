package io.aequicor.magicpaper.ui.screens

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.di.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.navigation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

/** A host without native registrations has no project screen: a link to one is refused, not answered with a stand-in. */
@OptIn(ExperimentalCoroutinesApi::class)
class AbsentSectionLinkTest {
    @Test fun savedProjectLinkIsRefusedWithANoticeAndLeavesTheUserInChat() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val lifecycle = LifecycleRegistry()
        val runtime = buildRuntime(InMemoryKeyValueStore(), persistenceStores(InMemoryDurableByteStore()),
            object : ProfileBridge {
                override val supportsFilePicker = false
                override suspend fun export(json: String) = false
                override suspend fun import(): String? = null
            }, NavigationSessionConfig(initialDeepLink = "magicpaper://projects/project/sessions/session"))
        try {
            runtime.koin.get<DefaultSettingsConfiguration>().changeSettings(AppSettings(onboardingDone = true)).getOrThrow()
            runtime.start()
            assertEquals(RuntimeState.Ready, runtime.ready.first { it != RuntimeState.Loading })
            val root = createAppRoot(runtime, DefaultComponentContext(lifecycle))
            root.awaitIdle(); runCurrent()
            assertEquals(AppRoute.Chat(), root.navigationState.value.route)
            assertEquals(NavigationMachine.SECTION_UNAVAILABLE, root.navigationState.value.error)
            assertTrue(root.navigationState.value.journal.visits.none { it.route is AppRoute.Projects })
        } finally { lifecycle.destroy(); runtime.close(); runtime.awaitClosed(); Dispatchers.resetMain() }
    }
}
