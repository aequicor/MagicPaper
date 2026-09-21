package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.SkillMachine.Fact
import io.aequicor.magicpaper.domain.SkillMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The representatives of [SkillSpace], kept here rather than in the api so a shipped binary carries
 * no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce. Every one is loaded at generation
 * `g1`, so it sits at revision 1 and the references and expectations below fit all of them.
 */
class SkillSpaceTest {
    private fun skill(id: String, name: String) =
        Skill(id, name, "description", "instruction", source = SkillSource.CATALOG, createdAt = 11, updatedAt = 12)
    private fun step(state: SkillMachine.State, input: SkillMachine.Input) = SkillMachine.reduce(state, input).state

    private val existing = skill("existing", "Existing")
    private val other = skill("other", "Other")

    private val new = SkillMachine.initial()
    private val empty = step(new, Fact.Initialized(emptyList(), "g1"))
    private val populated = step(new, Fact.Initialized(listOf(existing), "g1"))

    /**
     * Unconfirmed persistence fences before anything else, a store that was never loaded included. The
     * representatives reach it only from a loaded store, so they cannot tell the two orders apart.
     */
    @Test fun unconfirmedPersistenceOutranksAStoreThatWasNeverLoaded() {
        assertEquals(SkillSpace.PERSISTENCE_UNKNOWN, SkillSpace.label(step(new, Fact.PersistenceUnknown)))
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        SkillMachine,
        states = mapOf(
            SkillSpace.NEW to new,
            SkillSpace.EMPTY to empty,
            SkillSpace.POPULATED to populated,
            SkillSpace.PERSISTENCE_UNKNOWN to step(populated, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            SkillSpace.INSTALL to Intent.Install(SkillInstallBasis("g1", other.nameKey, null, null), other, 20),
            SkillSpace.SET_ENABLED to Intent.SetEnabled(SkillRef("existing", "g1", 1), enabled = false, at = 20),
            SkillSpace.DELETE to Intent.Delete(SkillRef("existing", "g1", 1)),
            SkillSpace.IMPORT to Intent.Import("g1", 1, listOf(other), "g3"),
            SkillSpace.CLEAR to Intent.Clear("g1", 1, "g2"),
            SkillSpace.INITIALIZED to Fact.Initialized(listOf(existing), "g1"),
            SkillSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
