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
    val taskWorktreeId: String? = null,
    val mediaCapabilities: Set<MediaKind> = emptySet(),
    /** Usage ownership is supplied by the host; ordinary chats have no coding project. */
    val usageScope: UsageScope? = null,
) {
    companion object {
        fun worker(session: CodingSession) = ToolExecutionContext(session.projectId, session.id, session.id,
            session.pendingRun?.runId ?: Id.new(), if (session.role == CodingSessionRole.WORKER) ToolRole.WORKER else ToolRole.CHAT,
            session.interactionMode, session.planId, stageId = session.stageId, parentSessionId = session.parentSessionId,
            organismId = session.organismId, runtimeGeneration = session.runtimeGeneration,
            planningRulesSnapshot = session.planningRulesSnapshot,
            taskWorktreeId = session.taskWorktree?.takeIf { it.taskId == session.pendingRun?.runId }?.taskId)
    }
}

data class ToolDefinition(
    val id: String, val description: String, val schema: JsonObject, val category: ToolCategory = ToolCategory.ACTION,
    val roles: Set<ToolRole> = ToolRole.entries.toSet(), val mutating: Boolean = false, val native: Boolean = false,
    val needsPlan: Boolean = false,
    val orchestration: Boolean = false,
    /** Every default is permissive: definitions built outside the catalogues declare nothing. */
    val modes: Set<CodingInteractionMode> = CodingInteractionMode.entries.toSet(),
    /** Run identity, not a mode: the caller's worktree task is its own run and not a plan stage. */
    val needsTaskWorktree: Boolean = false,
    /** An auxiliary run may call this application tool even though it mutates. */
    val auxiliarySafe: Boolean = false,
) {
    val wireName: String get() = "magicpaper_" + id.replace('.', '_')
    fun allowed(context: ToolExecutionContext): Boolean {
        val profile = context.mode.profile
        return context.role in roles && context.mode in modes &&
            (!orchestration || context.role in profile.orchestrationRoles) &&
            (!needsPlan || (context.planId != null && context.role in profile.planRoles)) &&
            (!context.auxiliaryExecution || native || !mutating || auxiliarySafe) &&
            (!needsTaskWorktree || (context.taskWorktreeId != null && context.stageId == null)) &&
            (!native || !mutating || context.role in profile.nativeMutationRoles)
    }
    /** A temporary verifier may read its owner's history in an application-granted CODE mode. */
    fun allowsAuthorityMode(context: ToolExecutionContext, ownerMode: CodingInteractionMode): Boolean =
        context.mode == ownerMode || (context.auxiliaryExecution && context.role in context.mode.profile.borrowedAuthorityRoles &&
            context.planId != null && !native &&
            (!mutating || auxiliarySafe))
    fun protocolDefinition() = buildJsonObject {
        put("name", wireName); put("description", description); put("inputSchema", schema)
        putJsonObject("annotations") { put("readOnlyHint", !mutating); put("destructiveHint", id == "session.delete") }
    }
}
