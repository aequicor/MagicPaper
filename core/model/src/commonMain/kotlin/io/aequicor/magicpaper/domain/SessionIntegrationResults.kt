package io.aequicor.magicpaper.domain

/** A result view changes no writer ownership, source copy, or native checkpoint. */
private fun SessionNode.integratedResultWorkspace(integration: SessionIntegration): SessionCodingWorkspace? {
    val own = workspace ?: return null
    val combined = integration.workspace ?: return null
    if (own.generation != generation || own.phase != SessionCodingWorkspacePhase.CAPTURED || own.resultSnapshot == null ||
        integration.phase != SessionIntegrationPhase.VERIFIED || integration.request.actorSessionId != id ||
        integration.request.generation != generation || integration.request.sourcePath != own.attempt.path ||
        integration.request.sourceSnapshot != own.resultSnapshot || integration.commitSha.isBlank() ||
        integration.snapshot.isBlank() || !combined.git || combined.integrationPath == own.attempt.path) return null
    return own.copy(workspace = combined, resultSnapshot = integration.snapshot,
        attempt = own.attempt.copy(path = combined.integrationPath, resultCommit = integration.commitSha,
            verificationSnapshot = integration.snapshot))
}

/** Later edits to the parent's own files invalidate every integration made from an older snapshot. */
fun SessionOrganism.completedResultWorkspace(sessionId: String, generation: Long): Pair<String?, SessionCodingWorkspace>? {
    val node = sessions[sessionId]?.takeIf { it.generation == generation } ?: return null
    val own = node.workspace?.takeIf { it.generation == generation && it.phase == SessionCodingWorkspacePhase.CAPTURED &&
        it.resultSnapshot != null && it.attempt.resultCommit.isNotBlank() } ?: return null
    val latest = integrations.values.mapNotNull { integration -> node.integratedResultWorkspace(integration)?.let { integration to it } }
        .maxByOrNull { (integration, _) -> audit.indexOfLast { it.action == "INTEGRATION_VERIFIED" &&
            it.operationId.substringBeforeLast("-checkpoint-") == integration.request.id } }
    return latest?.let { (integration, workspace) -> integration.request.id to workspace } ?: (null to own)
}

/** Resolves an immutable result reference, never silently substitutes a newer integration. */
fun SessionOrganism.resultWorkspace(result: SessionResult): SessionCodingWorkspace? {
    val node = sessions[result.sessionId]?.takeIf { it.generation == result.generation } ?: return null
    val record = result.integrationId?.let { id -> integrations[id]?.let { node.integratedResultWorkspace(it) } }
        ?: if (result.integrationId == null) node.workspace else return null
    return record?.takeIf { it.generation == result.generation && it.phase == SessionCodingWorkspacePhase.CAPTURED &&
        it.resultSnapshot == result.sourceVersion && it.attempt.resultCommit == result.commitSha && result.commitSha.isNotBlank() }
}

fun SessionIntegration.resultChecks(): List<String> = checkResults.map { check ->
    "${check.command.joinToString(" ")}: exit=${check.exitCode ?: "unknown"}\n${check.blockedReason ?: check.output}"
}
