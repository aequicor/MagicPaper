package io.aequicor.magicpaper.domain.checks

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name every journaled branch of [CommandCheckMachine.Input] is written under, pinned.
 *
 * The inputs are journaled and replayed after a restart. None of these branches had a `@SerialName`, so the
 * polymorphic discriminator of each is the full name of the class *with its nesting* and moving or renaming a
 * branch, or splitting its parent, would silently orphan every record written under the old name. Each branch
 * now names itself with exactly the string it has always been written under, so nothing stored has to be migrated.
 *
 * The names below were read from the encoder before any was pinned, and this test passed unchanged after. Only the
 * discriminator changes when a name is pinned, so the names are what is pinned; a class is named here by its own
 * serializer because swapping the pins of two branches would leave the set of names unchanged and decode the
 * records of one as the other.
 */
class CommandCheckMachineWireFormatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val pinned = setOf(
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.ArtifactsCommitted",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.AuthorityRecorded",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.AuthorityRestored",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.CompletionRecovered",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Exited",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Failed",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.GroupStopped",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.NeighbourMissing",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.NotDispatched",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.PersistenceUnknown",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.PreparationRejected",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.ProcessPrepared",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Restored",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Inspect",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Release",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Stop",
        "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Submit",
    )

    /** A branch added later must be pinned here too: the hierarchy holds exactly 17 names. */
    @Test fun everyBranchIsWrittenUnderItsPinnedName() {
        val sealed = CommandCheckMachine.Input.serializer().descriptor.getElementDescriptor(1)
        assertEquals(pinned, (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet())
        assertEquals(17, pinned.size)
    }

    @Test fun everyBranchClassIsWrittenUnderItsOwnPinnedName() {
        val owned = listOf(
            CommandCheckMachine.Input.Fact.ArtifactsCommitted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.ArtifactsCommitted",
            CommandCheckMachine.Input.Fact.AuthorityRecorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.AuthorityRecorded",
            CommandCheckMachine.Input.Fact.AuthorityRestored.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.AuthorityRestored",
            CommandCheckMachine.Input.Fact.CompletionRecovered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.CompletionRecovered",
            CommandCheckMachine.Input.Fact.Exited.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Exited",
            CommandCheckMachine.Input.Fact.Failed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Failed",
            CommandCheckMachine.Input.Fact.GroupStopped.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.GroupStopped",
            CommandCheckMachine.Input.Fact.NeighbourMissing.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.NeighbourMissing",
            CommandCheckMachine.Input.Fact.NotDispatched.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.NotDispatched",
            CommandCheckMachine.Input.Fact.PersistenceUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.PersistenceUnknown",
            CommandCheckMachine.Input.Fact.PreparationRejected.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.PreparationRejected",
            CommandCheckMachine.Input.Fact.ProcessPrepared.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.ProcessPrepared",
            CommandCheckMachine.Input.Fact.Restored.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Restored",
            CommandCheckMachine.Input.Intent.Inspect.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Inspect",
            CommandCheckMachine.Input.Intent.Release.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Release",
            CommandCheckMachine.Input.Intent.Stop.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Stop",
            CommandCheckMachine.Input.Intent.Submit.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Intent.Submit",
        )
        assertEquals(17, owned.size)
        for ((written, name) in owned) assertEquals(name, written)
        assertEquals(pinned, owned.map { it.second }.toSet())
    }

    @Test fun aRecordWrittenBeforeThePinningStillDecodes() {
        val records = listOf<Pair<CommandCheckMachine.Input, String>>(
            CommandCheckMachine.Input.Fact.PersistenceUnknown to """{"type":"io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.PersistenceUnknown"}""",
            CommandCheckMachine.Input.Fact.Restored to """{"type":"io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input.Fact.Restored"}""",
        )
        for ((value, stored) in records) {
            assertEquals(stored, json.encodeToString(CommandCheckMachine.Input.serializer(), value))
            assertEquals(value, json.decodeFromString(CommandCheckMachine.Input.serializer(), stored))
        }
    }
}
