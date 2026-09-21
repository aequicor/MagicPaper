package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeCodingModelSelectionTest {
    private val project = CodingProject("project", "Project", "/fake", 1)
    private val native = FeatureFlagOverride.INHERIT.with(FeatureFlag.NATIVE_CODING_MODELS, true)
    private val session = CodingSession("session", project.id, "Task", 1, engine = CodingEngine.CODEX, featureFlags = native)
    private val astra = CodingModel("openai", "gpt-6-astra", "GPT-6 Astra", levels = listOf("low", "medium", "max", "ultra"),
        defaultLevel = "medium", acceptsImages = true)
    private val textOnly = CodingModel("openai", "gpt-text", "Text only")
    private val snapshot = CodingModelSnapshot(CodingEngine.CODEX, listOf(astra, textOnly), 1)
    private val image = Attachment.fromBytes("clipboard.png", "image/png", byteArrayOf(1, 2, 3))
    private fun pick(model: CodingModel, level: String? = null) = CodingModelSelection(CodingEngine.CODEX, model.provider, model.id, level)

    private class Catalog(initial: Map<CodingEngine, CodingModelSnapshot>, var refresh: (CodingEngine) -> CodingModelRefresh) : CodingModelCatalog {
        override val snapshots = MutableStateFlow(initial)
        override suspend fun refresh(engine: CodingEngine) = refresh.invoke(engine)
    }
    private val runtime = object : CodingRuntime by NoopCodingRuntime {
        override val modelSources = mapOf(CodingEngine.CODEX to CodingModelSource { emptyList() })
    }
    private fun catalog() = Catalog(mapOf(CodingEngine.CODEX to snapshot)) { CodingModelRefresh.Refreshed(snapshot) }

    private suspend fun TestScope.opened(catalog: Catalog = catalog(), stored: CodingSession = session): Pair<ModelSettingsFixture, DefaultCodingService> {
        val f = ModelSettingsFixture(); f.seed()
        val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(project); repo.saveSession(stored)
        return f to f.prepareCoding(runtime, repo, models = catalog).also { runCurrent() }
    }
    private fun DefaultCodingService.sessionState() = state.value.coding.sessions.single().session

    @Test fun catalogAndEnginesWithACatalogReachTheUiState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (_, service) = opened()
            assertEquals(setOf(CodingEngine.CODEX), service.state.value.coding.nativeModelEngines)
            assertEquals(snapshot, service.state.value.coding.modelCatalogs.getValue(CodingEngine.CODEX))
            service.close()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun choiceIsSavedVerbatimBesideTheLegacySelectionAndSurvivesARestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val legacy = ModelSelection("profile", "legacy-model")
            val (f, service) = opened(stored = session.copy(modelSelection = legacy))
            service.selectNativeCodingModel(session.id, pick(astra, "ultra")); runCurrent()
            assertEquals(pick(astra, "ultra"), service.sessionState().codingModel)
            service.close()

            val restarted = f.prepareCoding(runtime, JsonCodingProjectRepository(f.kv, f.json), models = catalog()); runCurrent()
            assertEquals(pick(astra, "ultra"), restarted.sessionState().codingModel, "ultra must not be folded into max")
            assertEquals(legacy, restarted.sessionState().modelSelection)
            restarted.close()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun levelTheModelDoesNotDeclareIsRefusedWithAnExplanationAndNothingIsSaved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (_, service) = opened()
            service.selectNativeCodingModel(session.id, pick(astra, "xhigh")); runCurrent()
            assertNull(service.sessionState().codingModel)
            assertContains(assertNotNull(service.state.value.notice), "не поддерживает уровень xhigh")
            service.close()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun modelMissingFromTheCatalogIsRefusedInsteadOfBeingGuessed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (_, service) = opened()
            service.selectNativeCodingModel(session.id, CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-gone")); runCurrent()
            assertNull(service.sessionState().codingModel)
            assertContains(assertNotNull(service.state.value.notice), "нет в каталоге")
            service.close()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun sessionOutsideTheRolloutKeepsTheLegacyChoice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (_, service) = opened(stored = session.copy(featureFlags = FeatureFlagOverride.INHERIT))
            service.selectNativeCodingModel(session.id, pick(astra)); runCurrent()
            assertNull(service.sessionState().codingModel, "flag off and no saved native choice")
            service.close()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun projectDefaultIsKeptForNewSessionsOfTheSameEngine() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (f, service) = opened()
            service.selectNativeCodingModel(session.id, pick(astra, "max"), forProject = true); runCurrent()
            assertEquals(pick(astra, "max"), service.state.value.coding.projects.single().codingModel)
            service.close()
            val restarted = f.prepareCoding(runtime, JsonCodingProjectRepository(f.kv, f.json), models = catalog()); runCurrent()
            assertEquals(pick(astra, "max"), restarted.state.value.coding.projects.single().codingModel)
            restarted.close()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun imageIsRejectedByTheModelsOwnDeclarationBeforeAnythingIsSent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val (_, service) = opened()
            service.selectNativeCodingModel(session.id, pick(textOnly)); runCurrent()
            service.sendCodingPromptTo(session.id, "Что изображено?", listOf(image)); runCurrent()
            assertContains(assertNotNull(service.state.value.notice), "Модель Text only не поддерживает изображения")
            assertTrue(service.sessionState().pendingRun == null, "a rejected input must not become a run")
            service.close()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedRefreshKeepsTheOldListExplainsItAndClearsTheBusyMark() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val catalog = catalog().also { it.refresh = { engine -> CodingModelRefresh.Failed(engine, CodingModelRefreshFailure.FAILED, snapshot) } }
            val (_, service) = opened(catalog)
            service.refreshCodingModels(CodingEngine.CODEX)
            assertTrue(CodingEngine.CODEX in service.state.value.coding.refreshingModels)
            runCurrent()
            assertTrue(service.state.value.coding.refreshingModels.isEmpty())
            val notice = assertNotNull(service.state.value.notice)
            assertContains(notice, "Не удалось получить список моделей")
            assertContains(notice, "Показан прежний список")
            assertEquals(snapshot, service.state.value.coding.modelCatalogs.getValue(CodingEngine.CODEX))
            service.close()
        } finally { Dispatchers.resetMain() }
    }
}
