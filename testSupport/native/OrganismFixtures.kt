package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime

/** Mutable controls belong to test doubles; production coordinators receive immutable ports. */
class TestOrganismPorts : SessionOrganismExecution, SessionOrganismSourceAccess {
    var tree: SessionTreeRuntime? = null
    var nativeRuntime: CodingRuntime = NoopCodingRuntime
    var cancelQuestions: suspend (String) -> Unit = {}
    var prepareSession: suspend (CodingSession) -> CodingSession = { it }
    var externalPlanStop: suspend (String, kotlinx.coroutines.Job) -> Boolean = { _, _ -> true }
    var rootLeaseWaitMillis: Long = 60_000
    var startChild: suspend (CodingSession, SessionTask) -> Unit = { _, _ -> error("Runtime not installed") }
    var stopSubtree: suspend (Set<String>) -> Unit = { error("Runtime not installed") }
    var waitChildren: suspend (Set<String>) -> Unit = { error("Runtime not installed") }
    var canCreateCodeChild: suspend (CodingSession) -> Boolean = { false }
    var sourceProject: suspend (CodingSession, CodingProject) -> CodingProject = { _, project -> project }
    var sourceLease: suspend (CodingSession, CodingProject) -> WorkspaceLease? = { _, _ -> null }
    var inspectResult: suspend (SessionResult) -> String? = { null }
    var reconcilePlanRetry: suspend (PlanRetryRecoveryRequest) -> PlanRetryRecoveryProof? = { null }
    var reconcileUnknownOutcomes: suspend (SessionQuarantineRecoveryRequest) -> SessionQuarantineProof? = { null }
    var canRecreateAfterQuarantine: suspend (Set<String>) -> Boolean = { false }
    override suspend fun start(session: CodingSession, task: SessionTask, scopeOwner: String?) {
        val active = tree
        if (active != null) active.executions.start(session, task, scopeOwner) else startChild(session, task)
    }
    override suspend fun stop(sessionIds: Set<String>) { tree?.executions?.stop(sessionIds) ?: stopSubtree(sessionIds) }
    override suspend fun await(sessionIds: Set<String>) { tree?.executions?.await(sessionIds) ?: waitChildren(sessionIds) }
    override suspend fun canCreateCodeChild(session: CodingSession) = tree?.sources?.canCreateCodeChild(session) ?: canCreateCodeChild.invoke(session)
    override suspend fun project(session: CodingSession, project: CodingProject) = tree?.sources?.project(session, project) ?: sourceProject(session, project)
    override suspend fun lease(session: CodingSession, project: CodingProject) = tree?.sources?.lease(session, project) ?: sourceLease(session, project)
    override suspend fun inspect(result: SessionResult) = tree?.sources?.inspect(result) ?: inspectResult(result)
}

fun testOrganismService(store: SessionOrganismStore, projects: CodingProjectOwner, settings: SettingsRepository,
    ports: TestOrganismPorts = TestOrganismPorts(), sourceSnapshot: suspend (CodingProject) -> String? = { null }): SessionOrganismService =
    SessionOrganismService(store, projects, settings, sourceSnapshot, execution = ports, sources = ports,
        reconcilePlanRetry = { ports.reconcilePlanRetry(it) }, reconcileUnknownOutcomes = { ports.reconcileUnknownOutcomes(it) },
        canRecreateAfterQuarantine = { ids, _ -> ports.canRecreateAfterQuarantine(ids) })

fun testSessionTree(organisms: SessionOrganismService, projects: CodingProjectOwner,
    profiles: LlmProfileRepository, settings: SettingsRepository, ports: TestOrganismPorts,
    clock: () -> Long = io.aequicor.magicpaper.util.Id::now, planningWorkspace: PlanningWorkspace? = null): SessionTreeRuntime =
    SessionTreeRuntime(organisms, projects, profiles, settings, clock, planningWorkspace,
        runtimeProvider = { ports.nativeRuntime }, cancelQuestions = { ports.cancelQuestions(it) },
        prepareSession = { ports.prepareSession(it) }, externalPlanStop = { id, job -> ports.externalPlanStop(id, job) },
        rootLeaseWaitMillis = { ports.rootLeaseWaitMillis }).also { ports.tree = it }
