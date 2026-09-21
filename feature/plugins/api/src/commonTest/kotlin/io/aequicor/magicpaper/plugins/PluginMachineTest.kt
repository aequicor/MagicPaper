package io.aequicor.magicpaper.plugins

import io.aequicor.magicpaper.domain.PluginState
import kotlin.test.*

class PluginMachineTest {
    @Test fun unknownDominatesEveryPreferenceMutation() {
        val initialized = PluginMachine.reduce(PluginMachine.initial(), PluginMachine.Fact.Initialized(listOf(PluginState("a", false)))).state
        val unknown = PluginMachine.reduce(initialized, PluginMachine.Fact.PersistenceUnknown).state
        for (input in listOf(PluginMachine.Intent.Toggle("a", true), PluginMachine.Intent.Import(emptyList()),
            PluginMachine.Intent.Clear, PluginMachine.Fact.Initialized(emptyList()), PluginMachine.Fact.NeighbourMissing("a"))) {
            val result = PluginMachine.reduce(unknown, input)
            assertEquals(unknown, result.state)
            assertIs<PluginMachine.Effect.Reject>(result.effects.single())
        }
    }
    @Test fun invalidImportPreservesAllExistingPreferences() {
        val initialized = PluginMachine.reduce(PluginMachine.initial(), PluginMachine.Fact.Initialized(listOf(PluginState("missing", false, mapOf("private" to "keep"))))).state
        for (values in listOf(listOf(PluginState("a"), PluginState("a", false)), listOf(PluginState("")))) {
            val result = PluginMachine.reduce(initialized, PluginMachine.Intent.Import(values))
            assertEquals(initialized, result.state)
            assertIs<PluginMachine.Effect.Reject>(result.effects.single())
        }
        val result = PluginMachine.reduce(initialized, PluginMachine.Intent.Toggle("a", false))
        assertEquals(initialized.preferences["missing"], result.state.preferences["missing"])
    }
    @Test fun initializationIsOneTimeAndMissingNeighbourDoesNotChangePreferences() {
        val initial = PluginMachine.initial()
        assertIs<PluginMachine.Effect.Reject>(PluginMachine.reduce(initial, PluginMachine.Intent.Clear).effects.single())
        val initialized = PluginMachine.reduce(initial, PluginMachine.Fact.Initialized(listOf(PluginState("a")))).state
        assertIs<PluginMachine.Effect.Reject>(PluginMachine.reduce(initialized, PluginMachine.Fact.Initialized(emptyList())).effects.single())
        val missing = PluginMachine.reduce(initialized, PluginMachine.Fact.NeighbourMissing("a")).state
        assertEquals(initialized.preferences, missing.preferences)
        assertEquals("a", missing.missingPlugin)
    }
}
