package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.serialization.json.*

/** Only application code constructs this scope. It is never decoded from tool arguments. */
data class ToolExecutionContext(
    val projectId: String, val ownerSessionId: String, val sessionId: String, val requestId: String,
    val role: ToolRole, val mode: CodingInteractionMode,
    val planId: String? = null, val runId: String? = null, val stageId: String? = null,
    val attemptId: String? = null, val turnIndex: Int = 0,
    val sourceInput: OrchestrationInput? = null, val parentSessionId: String? = null,
    val organismId: String? = null, val runtimeGeneration: Long = 0, val stateVersion: Long? = null,
    val planningRulesSnapshot: PlanningRulesSnapshot? = null,
    /** Temporary verification/merge runtimes borrow history ownership, never lifecycle authority. */
    val auxiliaryExecution: Boolean = false,
) {
    companion object {
        fun worker(session: CodingSession) = ToolExecutionContext(session.projectId, session.id, session.id,
            session.pendingRun?.runId ?: Id.new(), if (session.role == CodingSessionRole.WORKER) ToolRole.WORKER else ToolRole.CHAT,
            session.interactionMode, session.planId, stageId = session.stageId, parentSessionId = session.parentSessionId,
            organismId = session.organismId, runtimeGeneration = session.runtimeGeneration,
            planningRulesSnapshot = session.planningRulesSnapshot)
    }
}

data class ToolDefinition(
    val id: String, val description: String, val schema: JsonObject, val category: ToolCategory = ToolCategory.ACTION,
    val roles: Set<ToolRole> = ToolRole.entries.toSet(), val mutating: Boolean = false, val native: Boolean = false,
    val needsPlan: Boolean = false,
    val orchestration: Boolean = false,
) {
    val wireName: String get() = "magicpaper_" + id.replace('.', '_')
    fun allowed(context: ToolExecutionContext): Boolean =
        (!orchestration || context.mode == CodingInteractionMode.PLANNING) && context.role in roles && (!needsPlan || context.planId != null) &&
        (!context.auxiliaryExecution || native || !mutating || id == "immunity.signal") &&
        (!needsPlan || context.role !in setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER) || context.mode == CodingInteractionMode.PLANNING) &&
        (id != "stage.handoff" || context.mode == CodingInteractionMode.CODE) &&
        (!native || !mutating || (context.mode == CodingInteractionMode.CODE && context.role in setOf(ToolRole.WORKER, ToolRole.CHAT))) &&
        (id != "research_check" || context.mode == CodingInteractionMode.RESEARCH)
    /** A temporary verifier may read its owner's history in an application-granted CODE mode. */
    fun allowsAuthorityMode(context: ToolExecutionContext, ownerMode: CodingInteractionMode): Boolean =
        context.mode == ownerMode || (context.auxiliaryExecution && context.role == ToolRole.CHAT &&
            context.mode == CodingInteractionMode.CODE && context.planId != null && !native &&
            (!mutating || id == "immunity.signal"))
    fun protocolDefinition() = buildJsonObject {
        put("name", wireName); put("description", description); put("inputSchema", schema)
        putJsonObject("annotations") { put("readOnlyHint", !mutating); put("destructiveHint", id == "session.delete") }
    }
}
