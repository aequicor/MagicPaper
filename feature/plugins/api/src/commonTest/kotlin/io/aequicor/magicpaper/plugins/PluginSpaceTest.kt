package io.aequicor.magicpaper.plugins

import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.machine.verifyStateSpace
import io.aequicor.magicpaper.plugins.PluginMachine.Fact
import io.aequicor.magicpaper.plugins.PluginMachine.Intent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The representatives of [PluginSpace], kept here rather than in the api so a shipped binary carries
 * no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce.
 */
class PluginSpaceTest {
    private fun step(state: PluginMachine.State, input: PluginMachine.Input) = PluginMachine.reduce(state, input).state

    private val new = PluginMachine.initial()
    private val ready = step(new, Fact.Initialized(emptyList()))

    /**
     * Unconfirmed persistence fences before anything else, a store that was never loaded included. The
     * representatives reach it only from a loaded store, so they cannot tell the two orders apart.
     */
    @Test fun unconfirmedPersistenceOutranksAStoreThatWasNeverLoaded() {
        assertEquals(PluginSpace.PERSISTENCE_UNKNOWN, PluginSpace.label(step(new, Fact.PersistenceUnknown)))
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        PluginMachine,
        states = mapOf(
            PluginSpace.NEW to new,
            PluginSpace.READY to ready,
            PluginSpace.READY_PLUGIN_MISSING to step(ready, Fact.NeighbourMissing("plugin")),
            PluginSpace.PERSISTENCE_UNKNOWN to step(ready, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            PluginSpace.TOGGLE to Intent.Toggle("plugin", true),
            PluginSpace.IMPORT to Intent.Import(listOf(PluginState("plugin"))),
            PluginSpace.CLEAR to Intent.Clear,
            PluginSpace.INITIALIZED to Fact.Initialized(emptyList()),
            PluginSpace.NEIGHBOUR_MISSING to Fact.NeighbourMissing("plugin"),
            PluginSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
