package io.aequicor.magicpaper.domain

import kotlin.test.*

class SkillMachineTest {
    private fun skill(id: String = "a", name: String = "Name", source: SkillSource = SkillSource.CATALOG) =
        Skill(id, name, "description", "instruction", source = source, createdAt = 11, updatedAt = 12)
    private fun initial(vararg values: Skill) = SkillMachine.reduce(SkillMachine.initial(),
        SkillMachine.Fact.Initialized(values.toList(), "generation-1")).state
    private fun rejected(state: SkillMachine.State, input: SkillMachine.Input) {
        val result = SkillMachine.reduce(state, input)
        assertEquals(state, result.state)
        assertTrue(result.effects.single() is SkillMachine.Effect.Reject)
    }

    @Test fun sameSourceUpdatePreservesIdentityAndRejectsConcurrentReuse() {
        val before = initial(skill())
        val install = SkillMachine.Intent.Install(before.installBasis("name"), skill("incoming", " NAME "), 20)
        val after = SkillMachine.reduce(before, install).state
        assertEquals(setOf("a"), after.skills.keys)
        assertEquals(11, after.skills.getValue("a").skill.createdAt)
        assertEquals(20, after.skills.getValue("a").skill.updatedAt)
        rejected(after, install)
        rejected(after, SkillMachine.Intent.Install(after.installBasis("name"), skill(source = SkillSource.SELF_MADE), 21))
    }

    @Test fun staleToggleCannotOverwriteNewInstructionsAndDeleteCannotAffectRecreatedName() {
        val before = initial(skill())
        val oldRef = checkNotNull(before.ref("a"))
        val edited = SkillMachine.reduce(before, SkillMachine.Intent.Install(before.installBasis("name"),
            skill().copy(instructions = "new instruction"), 20)).state
        rejected(edited, SkillMachine.Intent.SetEnabled(oldRef, false, 21))
        rejected(edited, SkillMachine.Intent.Delete(oldRef))
        val deleted = SkillMachine.reduce(edited, SkillMachine.Intent.Delete(checkNotNull(edited.ref("a")))).state
        val recreated = SkillMachine.reduce(deleted, SkillMachine.Intent.Install(deleted.installBasis("name"), skill("b"), 30)).state
        rejected(recreated, SkillMachine.Intent.Delete(oldRef))
        assertEquals("b", recreated.skills.values.single().skill.id)
    }

    @Test fun absentNameTombstonePreventsLateInstallAfterInstallAndDelete() {
        val before = initial()
        val delayed = SkillMachine.Intent.Install(before.installBasis("name"), skill("late"), 5)
        val installed = SkillMachine.reduce(before, SkillMachine.Intent.Install(before.installBasis("name"), skill(), 5)).state
        val deleted = SkillMachine.reduce(installed, SkillMachine.Intent.Delete(checkNotNull(installed.ref("a")))).state
        rejected(deleted, delayed)
        rejected(deleted, SkillMachine.Intent.Install(deleted.installBasis("name"), skill(), 10))
    }

    @Test fun clearAndImportRequireExactCatalogRevisionAndNeverReuseGeneration() {
        val before = initial(skill())
        val clear = SkillMachine.Intent.Clear(before.generation, before.catalogRevision, "generation-2")
        val changed = SkillMachine.reduce(before, SkillMachine.Intent.SetEnabled(checkNotNull(before.ref("a")), false, 15)).state
        rejected(changed, clear)
        val empty = SkillMachine.reduce(before, clear).state
        rejected(empty, SkillMachine.Intent.Install(before.installBasis("name"), skill("late"), 20))
        rejected(empty, SkillMachine.Intent.Clear(empty.generation, empty.catalogRevision, "generation-1"))
        val restored = SkillMachine.reduce(empty, SkillMachine.Intent.Import(empty.generation, empty.catalogRevision,
            listOf(skill()), "generation-3")).state
        assertEquals(skill(), restored.skills.getValue("a").skill)
        rejected(restored, SkillMachine.Intent.Delete(checkNotNull(before.ref("a"))))
    }

    @Test fun legacyDuplicateNamesStayVisibleButCannotBeSilentlyCoalesced() {
        val before = initial(skill(), skill("b", "NAME"))
        assertEquals(2, before.skills.size)
        rejected(before, SkillMachine.Intent.Install(before.installBasis("name"), skill("c"), 20))
        val one = SkillMachine.reduce(before, SkillMachine.Intent.Delete(checkNotNull(before.ref("a")))).state
        assertEquals(setOf("b"), one.skills.keys)
    }

    @Test fun reducerReplayIsDeterministicAndHasNoExternalEffects() {
        val initialized = SkillMachine.Fact.Initialized(listOf(skill()), "one")
        val initial = SkillMachine.reduce(SkillMachine.initial(), initialized).state
        val inputs = listOf(initialized, SkillMachine.Intent.SetEnabled(checkNotNull(initial.ref("a")), false, 20))
        fun replay() = inputs.fold(SkillMachine.initial()) { state, input ->
            SkillMachine.reduce(state, input).also { assertTrue(it.effects.isEmpty()) }.state
        }
        assertEquals(replay(), replay())
        val unknown = SkillMachine.reduce(replay(), SkillMachine.Fact.PersistenceUnknown).state
        rejected(unknown, SkillMachine.Intent.Delete(checkNotNull(unknown.ref("a"))))
    }
}
