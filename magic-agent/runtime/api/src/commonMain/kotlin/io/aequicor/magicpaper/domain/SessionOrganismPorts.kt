package io.aequicor.magicpaper.domain

/** Native producers belong to the runtime parent. The organism only admits their work. */
interface SessionOrganismExecution {
    suspend fun start(session: CodingSession, task: SessionTask, scopeOwner: String? = null)
    suspend fun stop(sessionIds: Set<String>)
    suspend fun await(sessionIds: Set<String>)
}

/** Resolve the actual workspace retained by a native session without exporting its handles. */
interface SessionOrganismSourceAccess {
    suspend fun canCreateCodeChild(session: CodingSession): Boolean
    suspend fun project(session: CodingSession, project: CodingProject): CodingProject
    suspend fun lease(session: CodingSession, project: CodingProject): WorkspaceLease?
    suspend fun inspect(result: SessionResult): String?
}
