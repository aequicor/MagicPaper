package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingUiNativeModelsTest {
    private val ui = CodingUi(nativeModelEngines = setOf(CodingEngine.CODEX))
    private val on = FeatureFlagState().with(FeatureFlag.NATIVE_CODING_MODELS, true)
    private val off = FeatureFlagState()
    private val session = CodingSession("s", "p", "n", 1, engine = CodingEngine.CODEX)

    @Test fun flagAndAnEngineWithACatalogSwitchTheSessionToNativeModels() {
        assertTrue(ui.usesNativeModels(session, on))
        assertFalse(ui.usesNativeModels(session, off), "the flag is the rollout switch")
    }

    @Test fun engineWithoutANativeCatalogStaysOnTheLegacyChoice() {
        assertFalse(ui.usesNativeModels(session.copy(engine = CodingEngine.PI), on))
        assertFalse(ui.usesNativeModels(session.copy(engine = null), on))
        assertFalse(CodingUi().usesNativeModels(session, on))
    }

    @Test fun stageAndPlanningSessionsAreNotMovedBecauseTheirAssignmentIsFrozenWithTheAttempt() {
        assertFalse(ui.usesNativeModels(session.copy(stageId = "stage", planId = "plan"), on))
        assertFalse(ui.usesNativeModels(session.copy(planningMode = true), on))
    }

    @Test fun savedNativeChoiceKeepsTheSessionNativeEvenWhenTheFlagIsTurnedOff() {
        val chosen = session.copy(codingModel = CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-6-astra"))
        assertTrue(ui.usesNativeModels(chosen, off), "the run honors the saved choice, so the UI must show it")
    }
}
