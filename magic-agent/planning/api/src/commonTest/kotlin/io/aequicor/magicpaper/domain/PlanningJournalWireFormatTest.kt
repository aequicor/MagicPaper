package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.CoordinationEvent
import io.aequicor.magicpaper.domain.planning.FinalAttemptMutation
import io.aequicor.magicpaper.domain.planning.PlanEvent
import io.aequicor.magicpaper.domain.planning.PlanRevisionEvent
import io.aequicor.magicpaper.domain.planning.StageEvent
import io.aequicor.magicpaper.domain.planning.StageMutation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name every journaled branch of the plan hierarchies is written under, pinned.
 *
 * `PlanningMachine.Input` is journaled (`PlanInputCommit`), and it carries these hierarchies inside it:
 * `Intent.Revise` holds a list of [PlanEvent], and the stage and final-attempt facts hold a [StageMutation]
 * and a [FinalAttemptMutation]. None of their branches had a `@SerialName`, so the polymorphic discriminator
 * of each is the full name of the class *with its nesting* — `...planning.StageEvent.Completed`, not
 * `...planning.StageMutation.Completed` — and moving or renaming a branch, or splitting its parent, would
 * silently orphan every record written under the old name. Each branch now names itself with exactly the
 * string it has always been written under, so nothing stored has to be migrated.
 *
 * The names below were read from the encoder before any was pinned, and this test passed unchanged after.
 * Only the discriminator changes when a name is pinned — the fields of a branch are not touched — so the
 * names are what is pinned; the four whole records at the end show the discriminator in place, both ways.
 */
class PlanningJournalWireFormatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val pinned = mapOf(
        "CoordinationEvent" to setOf(
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.DecisionRecorded",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.DeliveryRequested",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.HandoffRecorded",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.HandoffStatusRecorded",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.HandoffSubmitted",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.InboxAnswered",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.InboxDelivered",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.InboxPrepared",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.LateDeliveryObserved",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.QuestionsObserved",
            "io.aequicor.magicpaper.domain.planning.CoordinationEvent.ScheduledDeliveryRequested",
        ),
        "PlanRevisionEvent" to setOf(
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.AssignmentsRecovered",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.DialogueAppended",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.EngineRestored",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.InitialConfirmed",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyLinked",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyPeersObserved",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyQuestionsObserved",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalApplied",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalApproved",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalDeclined",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalPrepared",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.QuestionDelivered",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RefinementFinished",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RequestCleared",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RequestStarted",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.StageNumbersBound",
            "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.StageRenamed",
        ),
        "StageMutation" to setOf(
            "io.aequicor.magicpaper.domain.planning.StageEvent.AcceptanceRecorded",
            "io.aequicor.magicpaper.domain.planning.StageEvent.Captured",
            "io.aequicor.magicpaper.domain.planning.StageEvent.Completed",
            "io.aequicor.magicpaper.domain.planning.StageEvent.ConflictRequested",
            "io.aequicor.magicpaper.domain.planning.StageEvent.ConflictStarted",
            "io.aequicor.magicpaper.domain.planning.StageEvent.ConflictTurnEnded",
            "io.aequicor.magicpaper.domain.planning.StageEvent.EngineResolved",
            "io.aequicor.magicpaper.domain.planning.StageEvent.EventFired",
            "io.aequicor.magicpaper.domain.planning.StageEvent.Interrupted",
            "io.aequicor.magicpaper.domain.planning.StageEvent.MergeFinished",
            "io.aequicor.magicpaper.domain.planning.StageEvent.MergeStarted",
            "io.aequicor.magicpaper.domain.planning.StageEvent.PlannerDecided",
            "io.aequicor.magicpaper.domain.planning.StageEvent.Reconciled",
            "io.aequicor.magicpaper.domain.planning.StageEvent.TransportFailed",
            "io.aequicor.magicpaper.domain.planning.StageEvent.TurnRequested",
            "io.aequicor.magicpaper.domain.planning.StageEvent.UserAnswered",
            "io.aequicor.magicpaper.domain.planning.StageEvent.VerificationDecided",
            "io.aequicor.magicpaper.domain.planning.StageEvent.WorkerAccepted",
            "io.aequicor.magicpaper.domain.planning.StageEvent.WorkerAdmitted",
            "io.aequicor.magicpaper.domain.planning.StageEvent.WorkerStarting",
            "io.aequicor.magicpaper.domain.planning.StageEvent.WorkerTurnEnded",
            "io.aequicor.magicpaper.domain.planning.StageEvent.WorkspacePrepared",
        ),
        "FinalAttemptMutation" to setOf(
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.AcceptanceRecorded",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.Accepted",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryFinished",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryRequested",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryReviewed",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryStarted",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryTurnEnded",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.EngineResolved",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.Failed",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.LegacyReviewReopened",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.ProgressObserved",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.VerificationPrepared",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.VerificationStarted",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.VerificationTurnEnded",
            "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.WaiversApplied",
        ),
    )

    private fun written(serializer: KSerializer<*>): Set<String> {
        val sealed = serializer.descriptor.getElementDescriptor(1)
        return (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet()
    }

    @Test fun everyBranchIsWrittenUnderItsPinnedName() {
        assertEquals(pinned.getValue("CoordinationEvent"), written(CoordinationEvent.serializer()))
        assertEquals(pinned.getValue("PlanRevisionEvent"), written(PlanRevisionEvent.serializer()))
        assertEquals(pinned.getValue("StageMutation"), written(StageMutation.serializer()))
        assertEquals(pinned.getValue("FinalAttemptMutation"), written(FinalAttemptMutation.serializer()))
    }

    /** A branch added later must be pinned here too: the hierarchies hold exactly 11, 17, 22 and 15 names. */
    @Test fun theHierarchiesHoldExactlyThePinnedBranches() {
        assertEquals(listOf(11, 17, 22, 15), pinned.values.map { it.size })
        assertEquals(pinned.getValue("CoordinationEvent") + pinned.getValue("PlanRevisionEvent"), written(PlanEvent.serializer()))
    }

    /**
     * The set above says which names exist; this says which class owns which. Swapping the pins of two branches
     * leaves the set unchanged and would decode every stored record of one as the other, so each class is named
     * here by its own serializer.
     */
    @Test fun everyBranchClassIsWrittenUnderItsOwnPinnedName() {
        val owned = listOf(
        CoordinationEvent.DecisionRecorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.DecisionRecorded",
        CoordinationEvent.DeliveryRequested.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.DeliveryRequested",
        CoordinationEvent.HandoffRecorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.HandoffRecorded",
        CoordinationEvent.HandoffStatusRecorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.HandoffStatusRecorded",
        CoordinationEvent.HandoffSubmitted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.HandoffSubmitted",
        CoordinationEvent.InboxAnswered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.InboxAnswered",
        CoordinationEvent.InboxDelivered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.InboxDelivered",
        CoordinationEvent.InboxPrepared.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.InboxPrepared",
        CoordinationEvent.LateDeliveryObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.LateDeliveryObserved",
        CoordinationEvent.QuestionsObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.QuestionsObserved",
        CoordinationEvent.ScheduledDeliveryRequested.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.CoordinationEvent.ScheduledDeliveryRequested",
        PlanRevisionEvent.AssignmentsRecovered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.AssignmentsRecovered",
        PlanRevisionEvent.DialogueAppended.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.DialogueAppended",
        PlanRevisionEvent.EngineRestored.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.EngineRestored",
        PlanRevisionEvent.InitialConfirmed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.InitialConfirmed",
        PlanRevisionEvent.LegacyLinked.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyLinked",
        PlanRevisionEvent.LegacyPeersObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyPeersObserved",
        PlanRevisionEvent.LegacyQuestionsObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.LegacyQuestionsObserved",
        PlanRevisionEvent.ProposalApplied.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalApplied",
        PlanRevisionEvent.ProposalApproved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalApproved",
        PlanRevisionEvent.ProposalDeclined.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalDeclined",
        PlanRevisionEvent.ProposalPrepared.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.ProposalPrepared",
        PlanRevisionEvent.QuestionDelivered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.QuestionDelivered",
        PlanRevisionEvent.RefinementFinished.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RefinementFinished",
        PlanRevisionEvent.RequestCleared.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RequestCleared",
        PlanRevisionEvent.RequestStarted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RequestStarted",
        PlanRevisionEvent.StageNumbersBound.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.StageNumbersBound",
        PlanRevisionEvent.StageRenamed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.StageRenamed",
        StageEvent.AcceptanceRecorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.AcceptanceRecorded",
        StageEvent.Captured.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.Captured",
        StageEvent.Completed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.Completed",
        StageEvent.ConflictRequested.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.ConflictRequested",
        StageEvent.ConflictStarted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.ConflictStarted",
        StageEvent.ConflictTurnEnded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.ConflictTurnEnded",
        StageEvent.EngineResolved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.EngineResolved",
        StageEvent.EventFired.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.EventFired",
        StageEvent.Interrupted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.Interrupted",
        StageEvent.MergeFinished.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.MergeFinished",
        StageEvent.MergeStarted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.MergeStarted",
        StageEvent.PlannerDecided.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.PlannerDecided",
        StageEvent.Reconciled.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.Reconciled",
        StageEvent.TransportFailed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.TransportFailed",
        StageEvent.TurnRequested.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.TurnRequested",
        StageEvent.UserAnswered.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.UserAnswered",
        StageEvent.VerificationDecided.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.VerificationDecided",
        StageEvent.WorkerAccepted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.WorkerAccepted",
        StageEvent.WorkerAdmitted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.WorkerAdmitted",
        StageEvent.WorkerStarting.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.WorkerStarting",
        StageEvent.WorkerTurnEnded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.WorkerTurnEnded",
        StageEvent.WorkspacePrepared.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.StageEvent.WorkspacePrepared",
        FinalAttemptMutation.AcceptanceRecorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.AcceptanceRecorded",
        FinalAttemptMutation.Accepted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.Accepted",
        FinalAttemptMutation.DeliveryFinished.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryFinished",
        FinalAttemptMutation.DeliveryRequested.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryRequested",
        FinalAttemptMutation.DeliveryReviewed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryReviewed",
        FinalAttemptMutation.DeliveryStarted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryStarted",
        FinalAttemptMutation.DeliveryTurnEnded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.DeliveryTurnEnded",
        FinalAttemptMutation.EngineResolved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.EngineResolved",
        FinalAttemptMutation.Failed.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.Failed",
        FinalAttemptMutation.LegacyReviewReopened.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.LegacyReviewReopened",
        FinalAttemptMutation.ProgressObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.ProgressObserved",
        FinalAttemptMutation.VerificationPrepared.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.VerificationPrepared",
        FinalAttemptMutation.VerificationStarted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.VerificationStarted",
        FinalAttemptMutation.VerificationTurnEnded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.VerificationTurnEnded",
        FinalAttemptMutation.WaiversApplied.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.WaiversApplied",
        )
        assertEquals(65, owned.size)
        for ((written, pinnedName) in owned) assertEquals(pinnedName, written)
        assertEquals(pinned.values.flatten().toSet(), owned.map { it.second }.toSet())
    }

    @Test fun aRecordWrittenBeforeThePinningStillDecodes() {
        val records = listOf(
            PlanEvent.serializer() to (PlanRevisionEvent.RequestCleared("request") to """{"type":"io.aequicor.magicpaper.domain.planning.PlanRevisionEvent.RequestCleared","requestId":"request"}"""),
            StageMutation.serializer() to (StageEvent.Completed to """{"type":"io.aequicor.magicpaper.domain.planning.StageEvent.Completed"}"""),
            FinalAttemptMutation.serializer() to (FinalAttemptMutation.Accepted to """{"type":"io.aequicor.magicpaper.domain.planning.FinalAttemptMutation.Accepted"}"""),
            CoordinationEvent.serializer() to (CoordinationEvent.InboxAnswered("attempt", 1) to """{"type":"io.aequicor.magicpaper.domain.planning.CoordinationEvent.InboxAnswered","attemptId":"attempt","turnIndex":1}"""),
        )
        for ((serializer, pair) in records) {
            val (value, stored) = pair
            @Suppress("UNCHECKED_CAST") val typed = serializer as KSerializer<Any>
            assertEquals(stored, json.encodeToString(typed, value))
            assertEquals(value, json.decodeFromString(typed, stored))
        }
    }
}
