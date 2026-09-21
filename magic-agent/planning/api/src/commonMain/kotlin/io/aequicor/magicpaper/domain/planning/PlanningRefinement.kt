package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable

@Serializable data class RefinementRef(val planId: String, val projectId: String, val runId: String,
    val requestId: String, val admissionId: String)
@Serializable enum class RefinementMode { DIRECT, PROPOSAL }

/** Exact proposal basis without any executable attempt fields. */
@Serializable data class ProposalSpecification(val id: String, val baseRunId: String,
    val baseTree: List<DecisionNode>, val baseStages: List<StageSpecification>,
    val tree: List<DecisionNode>, val stages: List<StageSpecification>, val explanation: String) {
    companion object {
        fun from(proposal: PlanProposal) = ProposalSpecification(proposal.id, proposal.baseRunId,
            proposal.baseTree, proposal.baseMilestones.map(StageSpecification::from),
            proposal.tree, proposal.milestones.map(StageSpecification::from), proposal.explanation)
    }
}

/** Captured and retained by the owner when it accepts the request; completion supplies only ref + result. */
@Serializable data class RefinementRequest(val ref: RefinementRef, val baseRevision: Long,
    val basis: PlanSpecification, val dialogue: List<PlanningMessage>,
    val proposalBasis: ProposalSpecification?, val mode: RefinementMode,
    val selection: ModelSelection?, val search: SearchProvider)

@Serializable data class RefinementResult(val specification: PlanSpecification,
    val assistant: PlanningMessage, val step: PlanningStep? = null)

/** Called after the accepted user message and request ID have been written to the candidate projection. */
fun Plan.captureRefinement(requestId: String, admissionId: String, requireApproval: Boolean,
    selection: ModelSelection?, search: SearchProvider): RefinementRequest {
    require(requestId.isNotBlank() && admissionId.isNotBlank() && this.requestId == requestId) { "Запрос доработки изменился" }
    val proposalMode = (requireApproval && confirmedRevision != null) || phase == ExecutionPhase.COMPLETE ||
        proposal != null || (finalAttempt != null && !canExtendAfterFinalVerification)
    return RefinementRequest(RefinementRef(id, projectId, runId, requestId, admissionId), revision,
        PlanSpecification.from(this), dialogue, proposal?.let(ProposalSpecification::from),
        if (proposalMode) RefinementMode.PROPOSAL else RefinementMode.DIRECT, selection, search)
}

/** A single committed input applies the response and finishes only the request which produced it. */
fun Plan.finishRefinement(request: RefinementRequest, result: RefinementResult, at: Long): Plan {
    require(at >= 0 && request.ref.admissionId.isNotBlank() &&
        request.ref == RefinementRef(id, projectId, runId, requestId, request.ref.admissionId) && requestId.isNotBlank()) {
        "Ответ относится к другому запросу доработки"
    }
    require(request.basis == PlanSpecification.from(this) && request.dialogue == dialogue &&
        request.proposalBasis == proposal?.let(ProposalSpecification::from)) { "План изменился во время ответа оркестратора" }
    require(result.assistant.id.isNotBlank() && result.assistant.role == "assistant" &&
        dialogue.none { it.id == result.assistant.id }) { "Идентификатор ответа уже использован" }
    require(result.step == null || result.step in setOf(PlanningStep.CLARIFY, PlanningStep.REVIEW)) {
        "Ответ модели не может разрешать выполнение"
    }
    val next = when (request.mode) {
        RefinementMode.DIRECT -> {
            require(proposal == null) { "Сначала завершите рассмотрение предложения" }
            applySpecification(result.specification).copy(wizardStep = result.step ?: wizardStep)
        }
        RefinementMode.PROPOSAL -> {
            require(result.specification.goal == goal && result.specification.priorities == priorities &&
                result.specification.parallelism == parallelism) { "Предложение не может подменять цель и параметры плана" }
            // The proposal may revise safely interrupted work; acceptance reattaches its exact history.
            val candidate = refinementView().specificationCandidate(result.specification)
            copy(proposal = if (proposal == null && result.specification == request.basis) null else
                PlanProposal(request.ref.requestId, runId, tree, milestones, candidate.tree,
                    candidate.milestones, result.assistant.text))
        }
    }
    val changed = result.specification.tree != request.basis.tree || result.specification.stages != request.basis.stages
    return next.copy(dialogue = dialogue + result.assistant, pendingRequest = "", requestId = "",
        pendingRecalculationNodeId = null, plannerSelection = request.selection, searchProvider = request.search,
        versions = if (!changed) next.versions else next.versions + PlanVersion(request.baseRevision,
            request.basis.tree, request.basis.stages.map(StageSpecification::milestone), at))
}
