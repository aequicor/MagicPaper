package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name every journaled branch of [PlanningMachine.Input] is written under, pinned.
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
class PlanningMachineWireFormatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val pinned = setOf(
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.AcceptanceRechecked",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.Applied",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.AttemptRecorded",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.EvidenceObserved",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.FinalAttemptCleared",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.FinalAttemptCreated",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.FinalTransitioned",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.IssueObserved",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.JournalObserved",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.LegacyCheckpoint",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.LegacyImported",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.MergeAcceptanceRecorded",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.NativeObserved",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.OperationUnknown",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.PersistenceUnknown",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.PhaseObserved",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ProjectionConfirmed",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ProjectionFailed",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.RecoveryConfirmed",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.RefinementCompleted",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.Restored",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.RulesBound",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ScheduleAdvanced",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ScheduleDelivered",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ScheduleFailed",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.SkippedVerificationRestored",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StageCreated",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StageProgressObserved",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StageTransitioned",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StopConfirmed",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StopUnknown",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StrategySelected",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.VerificationObserved",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.WorkspacePrepared",
        "io.aequicor.magicpaper.domain.PlanningMachine.Fact.WorkspaceSelected",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.AssignStage",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.BeginRefinement",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.CancelRefinement",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.ConfirmNativeRecovery",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Create",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Delete",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.DiscardLegacyRefinement",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Edit",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Navigate",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Pause",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.RecoverAssignments",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.RefineRequested",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Resume",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Retry",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Revise",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Schedule",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.SkipVerification",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Start",
        "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Stop",
    )

    /** A branch added later must be pinned here too: the hierarchy holds exactly 54 names. */
    @Test fun everyBranchIsWrittenUnderItsPinnedName() {
        val sealed = PlanningMachine.Input.serializer().descriptor.getElementDescriptor(1)
        assertEquals(pinned, (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet())
        assertEquals(54, pinned.size)
    }

    @Test fun everyBranchClassIsWrittenUnderItsOwnPinnedName() {
        val owned = listOf(
            PlanningMachine.Fact.AcceptanceRechecked.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.AcceptanceRechecked",
            PlanningMachine.Fact.Applied.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.Applied",
            PlanningMachine.Fact.AttemptRecorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.AttemptRecorded",
            PlanningMachine.Fact.EvidenceObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.EvidenceObserved",
            PlanningMachine.Fact.FinalAttemptCleared.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.FinalAttemptCleared",
            PlanningMachine.Fact.FinalAttemptCreated.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.FinalAttemptCreated",
            PlanningMachine.Fact.FinalTransitioned.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.FinalTransitioned",
            PlanningMachine.Fact.IssueObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.IssueObserved",
            PlanningMachine.Fact.JournalObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.JournalObserved",
            PlanningMachine.Fact.LegacyCheckpoint.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.LegacyCheckpoint",
            PlanningMachine.Fact.LegacyImported.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.LegacyImported",
            PlanningMachine.Fact.MergeAcceptanceRecorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.MergeAcceptanceRecorded",
            PlanningMachine.Fact.NativeObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.NativeObserved",
            PlanningMachine.Fact.OperationUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.OperationUnknown",
            PlanningMachine.Fact.PersistenceUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.PersistenceUnknown",
            PlanningMachine.Fact.PhaseObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.PhaseObserved",
            PlanningMachine.Fact.ProjectionConfirmed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ProjectionConfirmed",
            PlanningMachine.Fact.ProjectionFailed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ProjectionFailed",
            PlanningMachine.Fact.RecoveryConfirmed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.RecoveryConfirmed",
            PlanningMachine.Fact.RefinementCompleted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.RefinementCompleted",
            PlanningMachine.Fact.Restored.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.Restored",
            PlanningMachine.Fact.RulesBound.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.RulesBound",
            PlanningMachine.Fact.ScheduleAdvanced.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ScheduleAdvanced",
            PlanningMachine.Fact.ScheduleDelivered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ScheduleDelivered",
            PlanningMachine.Fact.ScheduleFailed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.ScheduleFailed",
            PlanningMachine.Fact.SkippedVerificationRestored.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.SkippedVerificationRestored",
            PlanningMachine.Fact.StageCreated.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StageCreated",
            PlanningMachine.Fact.StageProgressObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StageProgressObserved",
            PlanningMachine.Fact.StageTransitioned.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StageTransitioned",
            PlanningMachine.Fact.StopConfirmed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StopConfirmed",
            PlanningMachine.Fact.StopUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StopUnknown",
            PlanningMachine.Fact.StrategySelected.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.StrategySelected",
            PlanningMachine.Fact.VerificationObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.VerificationObserved",
            PlanningMachine.Fact.WorkspacePrepared.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.WorkspacePrepared",
            PlanningMachine.Fact.WorkspaceSelected.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Fact.WorkspaceSelected",
            PlanningMachine.Intent.AssignStage.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.AssignStage",
            PlanningMachine.Intent.BeginRefinement.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.BeginRefinement",
            PlanningMachine.Intent.CancelRefinement.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.CancelRefinement",
            PlanningMachine.Intent.ConfirmNativeRecovery.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.ConfirmNativeRecovery",
            PlanningMachine.Intent.Create.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Create",
            PlanningMachine.Intent.Delete.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Delete",
            PlanningMachine.Intent.DiscardLegacyRefinement.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.DiscardLegacyRefinement",
            PlanningMachine.Intent.Edit.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Edit",
            PlanningMachine.Intent.Navigate.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Navigate",
            PlanningMachine.Intent.Pause.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Pause",
            PlanningMachine.Intent.RecoverAssignments.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.RecoverAssignments",
            PlanningMachine.Intent.RefineRequested.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.RefineRequested",
            PlanningMachine.Intent.Resume.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Resume",
            PlanningMachine.Intent.Retry.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Retry",
            PlanningMachine.Intent.Revise.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Revise",
            PlanningMachine.Intent.Schedule.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Schedule",
            PlanningMachine.Intent.SkipVerification.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.SkipVerification",
            PlanningMachine.Intent.Start.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Start",
            PlanningMachine.Intent.Stop.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningMachine.Intent.Stop",
        )
        assertEquals(54, owned.size)
        for ((written, name) in owned) assertEquals(name, written)
        assertEquals(pinned, owned.map { it.second }.toSet())
    }
}
