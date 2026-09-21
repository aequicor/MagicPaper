package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.aequicor.magicpaper.plugins.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsContributionsTest {
    @Test fun platformPageIsCreatedOnlyWhenItsSavedDestinationIsOpened() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val lifecycle = LifecycleRegistry()
        val fixture = ModelSettingsFixture()
        val service = fixture.prepareSettings()
        val plugins = fixture.plugins.also { it.start() }
        try {
            var creations = 0
            val nativePage = object : SettingsComponent {
                override val state = service.state
                override fun onAction(action: SettingsAction) = error("Unexpected settings action")
                @Composable override fun Content() = Unit
            }
            val registration = SettingsPageRegistration(SettingsPage.ENGINES,
                SettingsNavigationEntry("⚙", "Движки", "", SettingsOutput.Engines),
                SettingsComponent.Factory { _, input, _ ->
                    assertEquals(SettingsInput(SettingsPage.ENGINES), input)
                    creations++
                    nativePage
                })
            val registrations = mutableListOf(registration)
            val contributions = SettingsContributions(pages = registrations)
            registrations.clear()
            val factory = DefaultSettingsComponentFactory(service, plugins, fixture.draftRepository, fixture.draftBlobs, contributions)
            val context = DefaultComponentContext(lifecycle)
            assertIs<DefaultSettingsComponent>(factory.create(context, SettingsInput()) {})
            assertIs<DefaultSettingsComponent>(factory.create(context, SettingsInput(SettingsPage.COMPUTER)) {})
            assertEquals(0, creations)
            assertSame(nativePage, factory.create(context, SettingsInput(SettingsPage.ENGINES)) {})
            assertEquals(1, creations)
        } finally { lifecycle.destroy(); service.close(); plugins.close(); Dispatchers.resetMain() }
    }

    @Test fun duplicatePlatformPagesAreRejectedAtAssembly() {
        val entry = SettingsPageRegistration(SettingsPage.ENGINES,
            SettingsNavigationEntry("⚙", "Движки", "", SettingsOutput.Engines),
            SettingsComponent.Factory { _, _, _ -> error("Assembly must not create components") })
        assertFailsWith<IllegalArgumentException> { SettingsContributions(listOf(entry, entry)) }
    }
}
