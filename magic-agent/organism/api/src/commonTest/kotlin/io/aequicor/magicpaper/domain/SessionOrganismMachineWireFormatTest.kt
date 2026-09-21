package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name every journaled branch of [SessionOrganismMachine.Input] is written under, pinned.
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
class SessionOrganismMachineWireFormatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val pinned = setOf(
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.AcceptPlanResult",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Acknowledge",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Adopt",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Charge",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.ChargeAuxiliary",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.CheckpointIntegration",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.FinishAuxiliary",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.FinishImmunityIntervention",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.FinishStop",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.LegacyImported",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.LimitPolicyMigrated",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Observe",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.PersistenceUnknown",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Quarantine",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.ReconcileAndAuthorizePlanRetry",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.ReconcileInterruptedRun",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.RecordResult",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.RecordWorkspace",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.RequestFailureStop",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.ResolveSessionQuarantine",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Restored",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.AcceptImmunityIntervention",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.AdmitIntegration",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.AdmitPlanWorker",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.ApplyLimits",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.AuthorizePlanRetry",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.BeginAuxiliary",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.BeginRun",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.ChangeRootMode",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.Check",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.Command",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.DeleteHistoryByUser",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.DismissImmunityIntervention",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.InspectSignals",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.PrepareUserTurn",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.ProposeImmunityInterventions",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.RenameByUser",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.RequestUserStop",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.RestoreByUser",
        "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.SetArchiveVisibility",
    )

    /** A branch added later must be pinned here too: the hierarchy holds exactly 40 names. */
    @Test fun everyBranchIsWrittenUnderItsPinnedName() {
        val sealed = SessionOrganismMachine.Input.serializer().descriptor.getElementDescriptor(1)
        assertEquals(pinned, (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet())
        assertEquals(40, pinned.size)
    }

    @Test fun everyBranchClassIsWrittenUnderItsOwnPinnedName() {
        val owned = listOf(
            SessionOrganismMachine.Fact.AcceptPlanResult.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.AcceptPlanResult",
            SessionOrganismMachine.Fact.Acknowledge.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Acknowledge",
            SessionOrganismMachine.Fact.Adopt.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Adopt",
            SessionOrganismMachine.Fact.Charge.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Charge",
            SessionOrganismMachine.Fact.ChargeAuxiliary.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.ChargeAuxiliary",
            SessionOrganismMachine.Fact.CheckpointIntegration.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.CheckpointIntegration",
            SessionOrganismMachine.Fact.FinishAuxiliary.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.FinishAuxiliary",
            SessionOrganismMachine.Fact.FinishImmunityIntervention.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.FinishImmunityIntervention",
            SessionOrganismMachine.Fact.FinishStop.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.FinishStop",
            SessionOrganismMachine.Fact.LegacyImported.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.LegacyImported",
            SessionOrganismMachine.Fact.LimitPolicyMigrated.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.LimitPolicyMigrated",
            SessionOrganismMachine.Fact.Observe.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Observe",
            SessionOrganismMachine.Fact.PersistenceUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.PersistenceUnknown",
            SessionOrganismMachine.Fact.Quarantine.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Quarantine",
            SessionOrganismMachine.Fact.ReconcileAndAuthorizePlanRetry.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.ReconcileAndAuthorizePlanRetry",
            SessionOrganismMachine.Fact.ReconcileInterruptedRun.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.ReconcileInterruptedRun",
            SessionOrganismMachine.Fact.RecordResult.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.RecordResult",
            SessionOrganismMachine.Fact.RecordWorkspace.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.RecordWorkspace",
            SessionOrganismMachine.Fact.RequestFailureStop.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.RequestFailureStop",
            SessionOrganismMachine.Fact.ResolveSessionQuarantine.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.ResolveSessionQuarantine",
            SessionOrganismMachine.Fact.Restored.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.Restored",
            SessionOrganismMachine.Intent.AcceptImmunityIntervention.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.AcceptImmunityIntervention",
            SessionOrganismMachine.Intent.AdmitIntegration.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.AdmitIntegration",
            SessionOrganismMachine.Intent.AdmitPlanWorker.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.AdmitPlanWorker",
            SessionOrganismMachine.Intent.ApplyLimits.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.ApplyLimits",
            SessionOrganismMachine.Intent.AuthorizePlanRetry.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.AuthorizePlanRetry",
            SessionOrganismMachine.Intent.BeginAuxiliary.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.BeginAuxiliary",
            SessionOrganismMachine.Intent.BeginRun.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.BeginRun",
            SessionOrganismMachine.Intent.ChangeRootMode.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.ChangeRootMode",
            SessionOrganismMachine.Intent.Check.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.Check",
            SessionOrganismMachine.Intent.Command.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.Command",
            SessionOrganismMachine.Intent.DeleteHistoryByUser.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.DeleteHistoryByUser",
            SessionOrganismMachine.Intent.DismissImmunityIntervention.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.DismissImmunityIntervention",
            SessionOrganismMachine.Intent.InspectSignals.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.InspectSignals",
            SessionOrganismMachine.Intent.PrepareUserTurn.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.PrepareUserTurn",
            SessionOrganismMachine.Intent.ProposeImmunityInterventions.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.ProposeImmunityInterventions",
            SessionOrganismMachine.Intent.RenameByUser.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.RenameByUser",
            SessionOrganismMachine.Intent.RequestUserStop.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.RequestUserStop",
            SessionOrganismMachine.Intent.RestoreByUser.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.RestoreByUser",
            SessionOrganismMachine.Intent.SetArchiveVisibility.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.SetArchiveVisibility",
        )
        assertEquals(40, owned.size)
        for ((written, name) in owned) assertEquals(name, written)
        assertEquals(pinned, owned.map { it.second }.toSet())
    }
}
