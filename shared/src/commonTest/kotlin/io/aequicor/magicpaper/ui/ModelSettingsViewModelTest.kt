package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSettingsViewModelTest {
    @Test fun chatSelectionIsImmediateAndDoesNotChangeDefaultOrProvider() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepare()
            val before = fixture.profiles.load()
            val settings = vm.state.value.settings
            val choice = ModelSelection("openai", "variant:precise", EffortSelection.of(ReasoningEffort.HIGH))
            vm.selectChatModel(choice)
            assertEquals(choice, vm.state.value.current?.modelSelection)
            assertEquals(settings, vm.state.value.settings)
            assertEquals(before, fixture.profiles.load())
            assertEquals(choice, fixture.chats.session("first")?.modelSelection)
            vm.newSession()
            assertEquals("gpt-5.4", vm.state.value.current?.modelSelection?.modelId)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun oneButtonGeneratesAllFavoritesThroughTheOperationalDefault() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepare()
            vm.generateModelDescriptions()
            assertEquals(3, fixture.calls.size)
            assertTrue(fixture.calls.all { it.id == "openai" && it.modelId == "gpt-5.4" })
            assertEquals(3, fixture.planning.dossiers().size)
            assertFalse(vm.state.value.descriptionsGenerating)
            assertTrue(fixture.planning.dossiers().any { it.modelId == "variant:precise" && it.limitations.isNotBlank() })
        } finally { Dispatchers.resetMain() }
    }
}
