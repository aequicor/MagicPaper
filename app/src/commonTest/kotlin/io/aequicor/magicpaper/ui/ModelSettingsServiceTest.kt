package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSettingsServiceTest {
    @Test fun selectedChatEngineIsRememberedForNewSessions() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepareChat()
            vm.selectChatEngine(CodingEngine.CODEX)
            advanceUntilIdle()
            assertEquals(CodingEngine.CODEX, fixture.settings.load().defaultCodingEngine)
            assertEquals(CodingEngine.CODEX, vm.state.value.current?.engine)

            vm.newSession()
            assertEquals(CodingEngine.CODEX, vm.state.value.current?.engine)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun chatEngineCannotChangeAfterExecutionHasStarted() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepareChat()
            val before = vm.state.value.current?.engine
            vm.send("Начать")
            vm.selectChatEngine(CodingEngine.CODEX)
            advanceUntilIdle()
            assertNotEquals(CodingEngine.CODEX, fixture.chats.session("first")?.engine)
            assertEquals(before ?: CodingEngine.PI, fixture.settings.load().defaultCodingEngine)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun chatSelectionIsImmediateAndDoesNotChangeDefaultOrProvider() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepareChat()
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
            val vm = fixture.prepareSettings()
            vm.generateModelDescriptions()
            assertEquals(3, fixture.calls.size)
            assertTrue(fixture.calls.all { it.id == "openai" && it.modelId == "gpt-5.4" })
            assertEquals(3, fixture.planning.dossiers().size)
            assertFalse(vm.state.value.descriptionsGenerating)
            assertTrue(fixture.planning.dossiers().any { it.modelId == "variant:precise" && it.limitations.isNotBlank() })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedGenerationShowsEveryReasonAndKeepsSavedDescriptions() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepareSettings()
            val before = fixture.planning.dossiers()
            fixture.gatewayFailure = IllegalStateException("Subscription: model unavailable")
            vm.generateModelDescriptions()
            assertEquals(before, fixture.planning.dossiers())
            assertEquals(3, vm.state.value.descriptionsErrors.size)
            assertTrue(vm.state.value.descriptionsErrors.all { "model unavailable" in it })
            assertTrue(vm.state.value.descriptionsContext!!.contains("gpt-5.4", ignoreCase = true))
            assertFalse(vm.state.value.descriptionsGenerating)
            fixture.gatewayFailure = null
            fixture.searchHits = emptyList()
            fixture.calls.clear()
            vm.generateModelDescriptions()
            assertEquals(3, vm.state.value.descriptionsErrors.size)
            assertTrue(fixture.calls.isEmpty())
            assertEquals(before, fixture.planning.dossiers())
        } finally { Dispatchers.resetMain() }
    }
}
