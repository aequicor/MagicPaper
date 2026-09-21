package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*

/** Generation fences and quarantine are owned by the organism, including native connection abort. */
class OrganismToolAuthority(
    private val organisms: SessionOrganismService?,
    private val codingProjects: CodingProjectRepository?,
    private val codingRuntime: CodingRuntime?,
) : ToolRunAuthority {
    override fun contextDefaults(context: ToolExecutionContext): ToolExecutionContext {
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        val node = organism?.sessions?.get(context.ownerSessionId)
        return if (node == null) context else context.copy(organismId = organism.id, runtimeGeneration = node.generation,
            planningRulesSnapshot = node.rules)
    }
    override suspend fun authorizeReceipt(context: ToolExecutionContext, definition: ToolDefinition) {
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        if (organism != null) {
            val node = organisms.store.get(organism.id).sessions.getValue(context.ownerSessionId)
            require(node.generation == context.runtimeGeneration && definition.allowsAuthorityMode(context, node.mode)) { "Полномочия запуска отозваны" }
        }
    }
    override suspend fun authorizeTool(context: ToolExecutionContext, definition: ToolDefinition) {
        authorizeReceipt(context, definition)
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        // Per-command CAS belongs to dispatch; a long-lived tool session keeps only its generation fence.
        if (organism != null && definition.mutating && definition.id != "immunity.signal")
            organisms.store.check(organisms.authority(context.copy(stateVersion = null), organism))
    }
    override suspend fun unknownOutcome(context: ToolExecutionContext, receipt: ToolReceipt) {
        try {
            val session = codingProjects?.sessions(context.projectId)?.firstOrNull { it.id == context.ownerSessionId }
            if (session != null && organisms != null) {
                val organism = organisms.ensure(session)
                organisms.project(organisms.store.quarantine(organism.id, session.id, context.runtimeGeneration,
                    receipt.operationId.ifBlank { receipt.id }, "Неизвестный исход ${receipt.toolId}: ${receipt.error.ifBlank { receipt.result.toString() }}"))
            }
        } finally {
            // A native provider can execute file/shell tools without re-entering ToolExecutor.
            // Revoke that running connection as well; its owner joins/reconciles asynchronously.
            codingRuntime?.abort(context.sessionId)
        }
    }
}
