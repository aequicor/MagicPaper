package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.OrganismLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shell only sends intents to the ViewModel.  This protects the navigation
 * and persistence paths while keeping platform/window code out of the design
 * system.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellSettingsViewModelTest {
    @Test
    fun explicitAgentLimitsPersistAndCanBeRemovedWithoutChangingOtherSettings() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepare()
            val initial = vm.state.value.settings
            val configured = initial.copy(agentLimits = OrganismLimits(tokens = 900_000,
                durationMillis = 9_000_000, activeSessions = 4, depth = 3, retries = 0,
                queueSize = 20, contextCharacters = 30_000))
            vm.saveSettings(configured)
            advanceUntilIdle()
            assertEquals(configured, fixture.settings.load())
            assertEquals(configured, vm.state.value.settings)
            vm.saveSettings(configured.copy(agentLimits = OrganismLimits()))
            advanceUntilIdle()
            assertEquals(initial, fixture.settings.load())
            assertEquals(initial, vm.state.value.settings)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun navigationSidebarSettingsAndWelcomeKeepTheirExistingStateContracts() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepare()

            assertFalse(vm.state.value.sessionsPanelOpen)
            vm.toggleSessionsPanel()
            assertTrue(vm.state.value.sessionsPanelOpen)

            vm.open(Screen.SETTINGS)
            assertEquals(Screen.SETTINGS, vm.state.value.screen)

            val saved = vm.state.value.settings.copy(hideSystemSteps = true)
            vm.saveSettings(saved)
            advanceUntilIdle()
            assertEquals(saved, vm.state.value.settings)
            assertEquals(saved, fixture.settings.load())
            assertEquals("Настройки сохранены.", vm.state.value.notice)

            vm.restartOnboarding()
            assertTrue(vm.state.value.showWelcome)
            vm.finishOnboarding(saved)
            advanceUntilIdle()
            assertFalse(vm.state.value.showWelcome)
            assertEquals(Screen.CHAT, vm.state.value.screen)
            assertTrue(fixture.settings.load().onboardingDone)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
