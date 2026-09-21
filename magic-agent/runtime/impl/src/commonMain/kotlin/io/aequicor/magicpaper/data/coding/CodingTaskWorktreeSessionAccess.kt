package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*

/** Child evidence is admitted as a parent fact; the workspace owner never writes parent snapshots. */
class CodingTaskWorktreeSessionAccess(private val projects: CodingProjectOwner) : TaskWorktreeSessionAccess {
    override suspend fun session(projectId: String, sessionId: String): CodingSession? = projects.sessions(projectId).firstOrNull { it.id == sessionId }
    override suspend fun publish(projection: TaskWorktreeProjection) {
        projects.dispatch(projection.owner.projectId, CodingMachine.Fact.WorktreeProjected(
            CodingMachine.SessionRef(projection.owner.sessionId, projection.generation), projection.task,
            CodingMachine.ChildRevision(projection.stream, projection.sequence, projection.resetEpoch, projection.sequence.toString()), projection.unknown))
    }
}
