package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyPeerCommandReplayTest {
    private val reply = StageReply(StageReplyKind.RESULT, "Finished source work", changedFiles = listOf("src/A.kt"))
    private val sourceStage = Milestone("source-stage", "Source stage")
    private val source = Plan("source-plan", "project", "Source goal", parentSessionId = "source-session",
        milestones = listOf(sourceStage), coordination = listOf(CoordinationRecord("source-result", sourceStage.id, reply)))
    private val base = Milestone("base", "Target stage")
    private fun delivery(hash: String = "-2147483000", text: String = originalText, session: String = "source-session"): PlanDelivery {
        val id = "source-plan-source-stage-$hash-peer-base"
        return PlanDelivery(id, session, "base-followup-$id", text)
    }
    private fun target(delivery: PlanDelivery): Plan {
        val followup = base.copy(id = delivery.targetStageId, continuationOf = base.id, description = delivery.text,
            status = MilestoneStatus.PENDING, attempts = emptyList(), report = "", checkNote = "", dependsOn = listOf(base.id))
        return Plan("target-plan", "project", "Target goal", parentSessionId = "target-session",
            deliveries = listOf(delivery), milestones = listOf(base, followup), tree = listOf(
                DecisionNode("root", "Target goal", DecisionKind.GOAL, children = listOf(base.id, followup.id)),
                DecisionNode(base.id, base.title, DecisionKind.STAGE, stageId = base.id),
                DecisionNode(followup.id, followup.title, DecisionKind.STAGE, stageId = followup.id)))
    }

    @Test fun recordedHistoricalHashIsRecognizedAfterReopenWithoutChangingAnySavedId() {
        val delivery = delivery()
        val before = target(delivery)
        val reopened = Json.decodeFromString(Plan.serializer(), Json.encodeToString(Plan.serializer(), before))
        val recovered = reopened.cancelLegacyPeerCommands(listOf(source))
        assertEquals(listOf(base), recovered.milestones)
        assertEquals(listOf(delivery.copy(state = DeliveryState.CANCELLED)), recovered.deliveries)
        assertTrue(recovered.tree.none { it.id == delivery.targetStageId })
        assertEquals(recovered, recovered.cancelLegacyPeerCommands(listOf(source)), "Replay is idempotent")
    }

    @Test fun changedPayloadForeignSourceAndMalformedRecordedHashAreNotRecognized() {
        val candidates = listOf(delivery(text = "$originalText changed"), delivery(session = "foreign-session"),
            delivery(hash = ""), delivery(hash = "01"), delivery(hash = "+1"), delivery(hash = "2147483648"),
            delivery().copy(id = "foreign-plan-source-stage--2147483000-peer-base"))
        for (delivery in candidates) {
            val before = target(delivery)
            assertEquals(before, before.cancelLegacyPeerCommands(listOf(source)))
        }
        val before = target(delivery())
        assertEquals(before, before.cancelLegacyPeerCommands(listOf(source.copy(projectId = "foreign-project"))))
    }

    @Test fun consumedDeliveryAndStartedFollowupRetainTheirExecutionEvidence() {
        val consumed = target(delivery().copy(state = DeliveryState.DELIVERED))
        assertEquals(consumed, consumed.cancelLegacyPeerCommands(listOf(source)))
        val before = target(delivery()).let { plan -> plan.copy(milestones = plan.milestones.map { stage ->
            if (stage.continuationOf == null) stage else stage.copy(attempts = listOf(StageAttempt("attempt", "worker", StageAssignment("profile", "model"))))
        }) }
        val recovered = before.cancelLegacyPeerCommands(listOf(source))
        assertEquals(before.milestones, recovered.milestones, "An existing execution is never erased by legacy recognition")
        assertEquals(before.tree, recovered.tree)
        assertEquals(before.deliveries.single().id, recovered.deliveries.single().id)
        assertEquals(DeliveryState.CANCELLED, recovered.deliveries.single().state)
    }

    private companion object {
        const val originalText = "Сведения соседнего плана. Перед продолжением перечитай затронутые файлы, не перезаписывай чужие изменения. План «Source goal», этап «Source stage»: Finished source work\nИзменённые файлы: src/A.kt"
    }
}
