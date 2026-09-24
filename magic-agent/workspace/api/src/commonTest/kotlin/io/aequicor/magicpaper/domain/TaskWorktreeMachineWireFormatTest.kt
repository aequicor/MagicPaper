package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name every journaled branch of [TaskWorktreeMachine.Input] is written under, pinned.
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
class TaskWorktreeMachineWireFormatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val pinned = setOf(
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Captured",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Delivered",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Failed",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Imported",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Inspected",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.InspectionUnknown",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.InspectedUnapplied",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Integrated",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.NeighbourMissing",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Opened",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.OutcomeRecovered",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.PersistenceUnknown",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Refreshed",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Restored",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.VerificationFailed",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Verified",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.AcceptMerge",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.AttachResponse",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.BindRun",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Capture",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Deliver",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Handoff",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Inspect",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Integrate",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.NoteFailure",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Prepare",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Refresh",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.RetryVerification",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.ReturnForRepair",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.RevokeHandoff",
        "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Verify",
    )

    /** A branch added later must be pinned here too: the hierarchy holds exactly 31 names. */
    @Test fun everyBranchIsWrittenUnderItsPinnedName() {
        val sealed = TaskWorktreeMachine.Input.serializer().descriptor.getElementDescriptor(1)
        assertEquals(pinned, (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet())
        assertEquals(31, pinned.size)
    }

    @Test fun everyBranchClassIsWrittenUnderItsOwnPinnedName() {
        val owned = listOf(
            TaskWorktreeMachine.Input.Fact.Captured.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Captured",
            TaskWorktreeMachine.Input.Fact.Delivered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Delivered",
            TaskWorktreeMachine.Input.Fact.Failed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Failed",
            TaskWorktreeMachine.Input.Fact.Imported.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Imported",
            TaskWorktreeMachine.Input.Fact.Inspected.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Inspected",
            TaskWorktreeMachine.Input.Fact.InspectionUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.InspectionUnknown",
            TaskWorktreeMachine.Input.Fact.InspectedUnapplied.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.InspectedUnapplied",
            TaskWorktreeMachine.Input.Fact.Integrated.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Integrated",
            TaskWorktreeMachine.Input.Fact.NeighbourMissing.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.NeighbourMissing",
            TaskWorktreeMachine.Input.Fact.Opened.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Opened",
            TaskWorktreeMachine.Input.Fact.OutcomeRecovered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.OutcomeRecovered",
            TaskWorktreeMachine.Input.Fact.PersistenceUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.PersistenceUnknown",
            TaskWorktreeMachine.Input.Fact.Refreshed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Refreshed",
            TaskWorktreeMachine.Input.Fact.Restored.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Restored",
            TaskWorktreeMachine.Input.Fact.VerificationFailed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.VerificationFailed",
            TaskWorktreeMachine.Input.Fact.Verified.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Verified",
            TaskWorktreeMachine.Input.Intent.AcceptMerge.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.AcceptMerge",
            TaskWorktreeMachine.Input.Intent.AttachResponse.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.AttachResponse",
            TaskWorktreeMachine.Input.Intent.BindRun.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.BindRun",
            TaskWorktreeMachine.Input.Intent.Capture.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Capture",
            TaskWorktreeMachine.Input.Intent.Deliver.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Deliver",
            TaskWorktreeMachine.Input.Intent.Handoff.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Handoff",
            TaskWorktreeMachine.Input.Intent.Inspect.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Inspect",
            TaskWorktreeMachine.Input.Intent.Integrate.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Integrate",
            TaskWorktreeMachine.Input.Intent.NoteFailure.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.NoteFailure",
            TaskWorktreeMachine.Input.Intent.Prepare.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Prepare",
            TaskWorktreeMachine.Input.Intent.Refresh.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Refresh",
            TaskWorktreeMachine.Input.Intent.RetryVerification.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.RetryVerification",
            TaskWorktreeMachine.Input.Intent.ReturnForRepair.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.ReturnForRepair",
            TaskWorktreeMachine.Input.Intent.RevokeHandoff.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.RevokeHandoff",
            TaskWorktreeMachine.Input.Intent.Verify.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent.Verify",
        )
        assertEquals(31, owned.size)
        for ((written, name) in owned) assertEquals(name, written)
        assertEquals(pinned, owned.map { it.second }.toSet())
    }

    @Test fun aRecordWrittenBeforeThePinningStillDecodes() {
        val records = listOf<Pair<TaskWorktreeMachine.Input, String>>(
            TaskWorktreeMachine.Input.Fact.PersistenceUnknown to """{"type":"io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.PersistenceUnknown"}""",
            TaskWorktreeMachine.Input.Fact.Restored to """{"type":"io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact.Restored"}""",
        )
        for ((value, stored) in records) {
            assertEquals(stored, json.encodeToString(TaskWorktreeMachine.Input.serializer(), value))
            assertEquals(value, json.decodeFromString(TaskWorktreeMachine.Input.serializer(), stored))
        }
    }
}
