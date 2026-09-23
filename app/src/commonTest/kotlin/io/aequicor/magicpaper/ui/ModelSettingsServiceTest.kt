package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSettingsServiceTest {
    @Test fun disablingProviderPersistsAndExcludesItFromExecutionButKeepsItInPicker() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepareSettings()

            vm.setLlmProfileEnabled("openai", false)
            advanceUntilIdle()

            assertFalse(fixture.profiles.load().first { it.id == "openai" }.enabled)
            assertFalse(vm.state.value.availableLlmProfiles.any { it.id == "openai" })
            assertFalse(vm.state.value.modelPickerProfiles.first { it.id == "openai" }.enabled)
            assertEquals("anthropic", ProfileResolver.resolve(null as ChatSession?, vm.state.value.settings, vm.state.value.llmProfiles)?.id)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun newChatsUseProviderWithoutAssigningTheDesktopEngineDefault() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var chat: DefaultChatService? = null
        try {
            val fixture = ModelSettingsFixture()
            fixture.seed()
            fixture.settings.save(fixture.settings.load().copy(defaultCodingEngine = CodingEngine.CODEX))
            val vm = fixture.prepareChat().also { chat = it }
            vm.newSession()
            advanceUntilIdle()
            assertNull(vm.state.value.current?.engine)
            assertEquals(CodingEngine.CODEX, fixture.settings.load().defaultCodingEngine)
        } finally { chat?.close(); Dispatchers.resetMain() }
    }

    @Test fun providerChatPreservesAnExistingDesktopEngineIdentity() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var chat: DefaultChatService? = null
        try {
            val fixture = ModelSettingsFixture()
            fixture.seed()
            fixture.chats.save(checkNotNull(fixture.chats.session("first")).copy(
                engine = CodingEngine.CODEX, nativeSessionId = "saved-native-conversation"))
            val vm = fixture.prepareChat().also { chat = it }
            vm.send("Начать")
            advanceUntilIdle()
            assertEquals(CodingEngine.CODEX, fixture.chats.session("first")?.engine)
            assertEquals("saved-native-conversation", fixture.chats.session("first")?.nativeSessionId)
            assertEquals(CodingEngine.PI, fixture.settings.load().defaultCodingEngine)
            assertTrue(fixture.calls.isNotEmpty(), "The saved engine identity does not prevent provider chat")
        } finally { chat?.close(); Dispatchers.resetMain() }
    }

    @Test fun chatSelectionIsImmediateAndDoesNotChangeDefaultOrProvider() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var chat: DefaultChatService? = null
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepareChat().also { chat = it }
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
        } finally { chat?.close(); Dispatchers.resetMain() }
    }
    @Test fun oneButtonGeneratesAllFavoritesThroughTheOperationalDefault() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepareSettings()
            vm.generateModelDescriptions()
            assertEquals(3, fixture.calls.size)
            assertTrue(fixture.calls.all { it.id == "openai" && it.modelId == "gpt-5.4" })
            assertEquals(3, fixture.modelDossiers.dossiers().size)
            assertFalse(vm.state.value.descriptionsGenerating)
            assertTrue(fixture.modelDossiers.dossiers().any { it.modelId == "variant:precise" && it.limitations.isNotBlank() })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedGenerationShowsEveryReasonAndKeepsSavedDescriptions() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepareSettings()
            val before = fixture.modelDossiers.dossiers()
            fixture.gatewayFailure = IllegalStateException("private-provider-payload: model unavailable")
            vm.generateModelDescriptions()
            assertEquals(before, fixture.modelDossiers.dossiers())
            assertEquals(3, vm.state.value.descriptionsErrors.size)
            assertTrue(vm.state.value.descriptionsErrors.all { "Прежнее описание сохранено" in it })
            assertTrue(vm.state.value.descriptionsErrors.none { "private-provider-payload" in it || "model unavailable" in it })
            assertTrue(vm.state.value.descriptionsContext!!.contains("gpt-5.4", ignoreCase = true))
            assertFalse(vm.state.value.descriptionsGenerating)
            fixture.gatewayFailure = null
            fixture.searchHits = emptyList()
            fixture.calls.clear()
            vm.generateModelDescriptions()
            assertEquals(3, vm.state.value.descriptionsErrors.size)
            assertTrue(fixture.calls.isEmpty())
            assertEquals(before, fixture.modelDossiers.dossiers())
        } finally { Dispatchers.resetMain() }
    }
}
