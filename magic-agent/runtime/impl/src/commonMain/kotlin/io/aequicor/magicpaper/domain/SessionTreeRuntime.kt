package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Child jobs belong to a live parent scope, even when its model has finished streaming. */
class SessionTreeRuntime(
    private val organisms: SessionOrganismService,
    private val projects: CodingProjectOwner,
    private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long = Id::now,
    private val planningWorkspace: PlanningWorkspace? = null,
    private val runtimeProvider: () -> CodingRuntime,
    private val cancelQuestions: suspend (String) -> Unit,
    private val prepareSession: suspend (CodingSession) -> CodingSession = { it },
    private val externalPlanStop: suspend (String, Job) -> Boolean = { _, _ -> true },
    private val rootLeaseWaitMillis: () -> Long = { 60_000L },
) {
    private fun retainCleanupFailure(primary: Throwable, cleanup: Throwable) {
        // Coroutine stack recovery can replace an exception with a same-type cause wrapper.
        // Retain the secondary cause on that original too, before another suspension unwraps it.
        var current = primary
        val visited = mutableSetOf<Throwable>()
        while (visited.add(current)) {
            if (current !== cleanup && current.suppressedExceptions.none { it === cleanup }) current.addSuppressed(cleanup)
            val cause = current.cause ?: break
            if (cause::class != primary::class) break
            current = cause
        }
    }
    /** Cleanup completes under NonCancellable, then restores cancellation control flow. */
    private inner class CleanupFailures(private val primary: Throwable? = null) {
        private var cancelled: CancellationException? = null
        fun record(sessionId: String, operation: String, failure: Exception) {
            primary?.let { retainCleanupFailure(it, failure) }
            if (failure is CancellationException) cancelled = cancelled ?: failure
            organisms.executionFailed(sessionId, operation, failure)
        }
        fun finish() { if (primary == null) cancelled?.let { throw it } }
    }
    private data class RootLease(val sessionId: String, val generation: Long, val owner: CodingProject, val workspaceLease: WorkspaceLease)
    private data class Handle(val owner: Job, val children: CompletableJob, var failed: Boolean = false,
        var generation: Long = 0, var closing: Boolean = false, val usage: MutableMap<String, Long> = mutableMapOf(),
        var workspace: SessionCodingWorkspaces.Lease? = null, var directRootLease: RootLease? = null,
        var auxiliary: SessionAuxiliaryRun? = null,
        var executionProject: CodingProject? = null, var report: () -> String = { "" })
    /** Captured at beginRun, never replaced by a newer generation when an old producer finishes. */
    private class RunCapture(var generation: Long) : AbstractCoroutineContextElement(Key) {
        var entered = false
        var request: CodingMachine.RunRef? = null
        var report: () -> String = { "" }
        companion object Key : CoroutineContext.Key<RunCapture>
    }
    private val lock = Mutex()
    private val handles = mutableMapOf<String, Handle>()
    private val children = mutableMapOf<String, Job>()
    private val runtimeParents = mutableMapOf<String, String>()
    private val retainedRootLeases = mutableMapOf<String, RootLease>()
    private var closing = false
    private val codingWorkspaces = planningWorkspace?.let { SessionCodingWorkspaces(it, organisms.store) }
    val runtime: CodingRuntime get() = runtimeProvider()
    suspend fun failed(sessionId: String) { lock.withLock { handles[sessionId]?.failed = true } }
    internal suspend fun nativeSessionStarted(session: CodingSession, nativeSessionId: String) {
        if (nativeSessionId.isBlank()) return
        val run = projects.states.value[session.projectId]?.runs?.get(session.id) ?: return
        if (run.executionGeneration != session.runtimeGeneration) return
        projects.dispatch(session.projectId, CodingMachine.Fact.NativeSessionBound(run.ref, nativeSessionId))
    }
    private suspend fun recordStopped(projectId: String, sessionId: String, unknown: Boolean) {
        val run = projects.states.value[projectId]?.runs?.get(sessionId) ?: return
        if (run.phase !in setOf(CodingMachine.Phase.RUNNING, CodingMachine.Phase.STOPPING)) return
        projects.dispatch(projectId, CodingMachine.Fact.RunStopped(run.ref, unknown))
    }
    suspend fun incoming(session: CodingSession): List<SessionDelivery> = organisms.pendingContext(session)
    suspend fun processed(session: CodingSession, ids: List<String>) = organisms.acknowledgeContext(session, ids)
    suspend fun auxiliaryContext(context: ToolExecutionContext): ToolExecutionContext {
        if (context.organismId != null) {
            organisms.synchronizeLimits(checkNotNull(context.organismId))
            return context
        }
        val owner = projects.sessions(context.projectId).firstOrNull { it.id == context.ownerSessionId }
            ?: error("Владелец вспомогательного запуска не сохранён")
        require(owner.runtimeGeneration == context.runtimeGeneration) { "Владелец другого поколения" }
        val organism = organisms.ensure(owner)
        val node = organism.sessions.getValue(owner.id)
        require(node.generation == context.runtimeGeneration) { "Поколение владельца изменилось при восстановлении" }
        return context.copy(organismId = organism.id, planningRulesSnapshot = node.rules)
    }

    suspend fun <T> withAuxiliaryScope(session: CodingSession, context: ToolExecutionContext, block: suspend (CodingSession) -> T): T = coroutineScope {
        require(session.id == context.sessionId && session.projectId == context.projectId) { "Идентификатор вспомогательного запуска не совпадает" }
        val bound = auxiliaryContext(context)
        val organismId = bound.organismId!!
        val children = SupervisorJob(currentCoroutineContext().job)
        val handle = Handle(currentCoroutineContext().job, children, generation = bound.runtimeGeneration)
        var registered = false; var failure: Throwable? = null
        try {
            lock.withLock {
                check(!closing && session.id !in handles) { "Вспомогательная рабочая область занята" }
                handles[session.id] = handle; registered = true
            }
            val auxiliary = organisms.store.beginAuxiliary(bound.organismAdmission())
            handle.auxiliary = auxiliary
            withConfiguredLimits(organismId) {
                val result = block(session.copy(organismId = organismId, runtimeGeneration = auxiliary.generation,
                    planningRulesSnapshot = bound.planningRulesSnapshot))
                lock.withLock { handle.closing = true }
                if (handle.failed) children.cancel() else children.complete()
                children.join()
                result
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally { withContext(NonCancellable) {
            val cleanup = CleanupFailures(failure)
            try {
                children.cancelAndJoin()
                var uncertain = false
                try { cancelQuestions(session.id) } catch (error: Exception) { uncertain = true; cleanup.record(session.id, "questions_revoke", error) }
                try { runtime.reconcileDecided(session.id) }
                catch (recovery: NativeRunRecoveryRequired) {
                    // As for a session's own run: a stop its owner asked for is settled by a proven exit.
                    val owner = handle.auxiliary?.let { organisms.store.get(organismId).sessions[it.ownerSessionId] }
                    if (recovery.recovery.exitProven && owner?.desired in setOf(SessionDesiredState.STOP, SessionDesiredState.PAUSE))
                        AppLog.info("organism", "stop.outcome_unknown", mapOf("sessionId" to session.id, "result" to "exit_proven"))
                    else { uncertain = true; cleanup.record(session.id, "native_reconcile", recovery) }
                }
                catch (error: Exception) { uncertain = true; cleanup.record(session.id, "native_reconcile", error) }
                handle.auxiliary?.let { run ->
                    val observed = when { uncertain -> SessionObservedState.UNKNOWN; failure is CancellationException -> SessionObservedState.STOPPED
                        failure != null || handle.failed -> SessionObservedState.FAILED; else -> SessionObservedState.COMPLETED }
                    organisms.project(organisms.store.finishAuxiliary(organismId, run.id, observed))
                }
            } catch (error: Exception) {
                if (failure == null) throw error
                cleanup.record(session.id, "auxiliary_cleanup", error)
            } finally {
                if (registered) lock.withLock { if (handles[session.id] === handle) handles.remove(session.id) }
                cleanup.finish()
            }
        } }
    }
    /** Занятая папка — ограниченное ожидание со сверкой удержаний, а не мгновенный отказ всему запуску. */
    suspend fun projectForExecution(project: CodingProject, session: CodingSession): CodingProject {
        var waited = 0L
        while (true) {
            try {
                return executionProjectFor(project, session)
            } catch (busy: RootLeaseBusy) {
                // A retained lease behind a native run that cannot prove its own outcome will never
                // free itself through blind reconcile retries: only an explicit recovery acknowledgement
                // (on the blocking session) clears it. Give release one real attempt, then stop grinding
                // the same unresolvable reconcile every poll for the rest of the wait budget.
                if (waited > 0L) unresolvedRecoveryBlocking(busy.path)?.let { blocker ->
                    val busyError = TaskWorkspaceBusy(busy.path, WORKING_FOLDER)
                    AppLog.error("coding.workspace", "lease.recovery_required", busyError,
                        mapOf("sessionId" to session.id, "holder" to blocker, "waitedMillis" to waited.toString()))
                    throw busyError
                }
                if (waited >= rootLeaseWaitMillis()) {
                    val busyError = TaskWorkspaceBusy(busy.path, WORKING_FOLDER)
                    AppLog.error("coding.workspace", "lease.busy", busyError,
                        mapOf("sessionId" to session.id, "holder" to planningWorkspace?.holderOf(busy.path).orEmpty(),
                            "waitedMillis" to waited.toString()))
                    throw busyError
                }
                if (waited == 0L) AppLog.info("coding.workspace", "lease.waiting",
                    mapOf("sessionId" to session.id, "holder" to planningWorkspace?.holderOf(busy.path).orEmpty()))
                // Освобождение чужих и своих устаревших удержаний нельзя выполнять под замком рабочей области,
                // и повторяемо: доказательство остановки может появиться в любой момент ожидания.
                releaseRetainedRootLeases(session.id)
                releaseUnownedRootLeases()
                delay(ROOT_LEASE_POLL_MILLIS)
                waited += ROOT_LEASE_POLL_MILLIS
            }
        }
    }

    /** Read-only: never repeats the forced-kill attempt that [releaseRetainedRootLeases] already made. */
    private suspend fun unresolvedRecoveryBlocking(path: String): String? {
        val blocker = lock.withLock { retainedRootLeases.values.firstOrNull { it.owner.path == path } } ?: return null
        val recovery = runtime.recovery?.inspect(blocker.sessionId) ?: return null
        // A human already acknowledged item does not need a known outcome to count as resolved,
        // matching the same rule the native journal applies when restarting that same session.
        val unresolved = !recovery.decided
        return blocker.sessionId.takeIf { unresolved }
    }

    private class RootLeaseBusy(val path: String) : IllegalStateException()

    private suspend fun executionProjectFor(project: CodingProject, session: CodingSession): CodingProject = lock.withLock {
        val handle = handles[session.id] ?: error("Рабочая область не запущена")
        require(handle.generation == session.runtimeGeneration) { "Рабочая область другого поколения" }
        val node = session.organismId?.let { organisms.store.get(it).sessions[session.id] }
        if (planningWorkspace != null && node?.kind == SessionKind.ZYGOTE && session.interactionMode == CodingInteractionMode.CODE &&
            session.planId == null && session.stageId == null && handle.directRootLease == null) {
            // A generation owns its lease; the workspace port excludes all other writers to this path.
            val owner = project.copy(id = "root-${session.id}-${session.runtimeGeneration}")
            withContext(NonCancellable) {
                val acquired = planningWorkspace.acquire(owner, Id.new()) ?: throw RootLeaseBusy(project.path)
                handle.directRootLease = RootLease(session.id, session.runtimeGeneration, owner, acquired)
            }
        }
        (handle.workspace?.let { project.copy(path = it.record.attempt.path) } ?: project).also { handle.executionProject = it }
    }

    /**
     * Прерванная очистка держит блокировку до доказательства остановки исполнителя; повторная сверка —
     * то же доказательство, что и явная остановка. Освобождается только блокировка этой сессии.
     */
    private suspend fun releaseRetainedRootLeases(sessionId: String): Boolean {
        val workspaces = planningWorkspace ?: return false
        val native = runtime
        var released = false
        while (true) {
            val candidate = lock.withLock { retainedRootLeases.values.firstOrNull { it.sessionId == sessionId } }
                ?: return released
            try { native.reconcile(candidate.sessionId) }
            catch (unresolved: NativeRunRecoveryRequired) {
                // reconcile() itself always still demands a known outcome (other callers depend on
                // that strict contract). Releasing this session's own lease only needs proof that a
                // human explicitly reviewed the uncertain outcome, which this checks independently.
                if (!acknowledgedDespiteUnknownOutcome(candidate.sessionId)) {
                    AppLog.error("coding.workspace", "lease.reconcile.failed", unresolved, mapOf("sessionId" to sessionId))
                    return released
                }
            }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                AppLog.error("coding.workspace", "lease.reconcile.failed", failure, mapOf("sessionId" to sessionId))
                return released
            }
            try { workspaces.release(candidate.workspaceLease) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // Без подтверждения освобождения владелец обязан остаться: иначе папку откроют два писателя.
                AppLog.error("coding.workspace", "lease.release.failed", failure, mapOf("sessionId" to sessionId))
                return released
            }
            lock.withLock { if (retainedRootLeases[candidate.owner.id] === candidate) retainedRootLeases.remove(candidate.owner.id) }
            AppLog.info("coding.workspace", "lease.reclaimed",
                mapOf("sessionId" to sessionId, "generation" to candidate.generation.toString()))
            released = true
        }
    }

    private suspend fun acknowledgedDespiteUnknownOutcome(sessionId: String): Boolean {
        val recovery = runtime.recovery?.inspect(sessionId) ?: return false
        return recovery.items.isNotEmpty() && recovery.decided
    }

    /**
     * Задача, которой мешает занятая папка, вправе снять чужое удержание, если у сессии уже нет
     * рабочей области: доказательство даёт движок, а не состояние интерфейса. Вызывается вне
     * блокировки рабочей области, потому что освобождение самой себя под этим замком зависло бы.
     */
    suspend fun releaseUnownedRootLeases(): Boolean {
        var released = false
        while (true) {
            val candidate = lock.withLock { retainedRootLeases.values.firstOrNull { it.sessionId !in handles } } ?: return released
            if (!releaseRetainedRootLeases(candidate.sessionId)) return released
            released = true
        }
    }

    suspend fun sourceProject(session: CodingSession, project: CodingProject): CodingProject = lock.withLock {
        val handle = handles[session.id] ?: error("Рабочая область не запущена")
        require(handle.generation == session.runtimeGeneration) { "Рабочая область другого поколения" }
        handle.workspace?.let { project.copy(path = it.record.attempt.path) } ?: handle.executionProject ?: project
    }

    val executions = object : SessionOrganismExecution {
        override suspend fun start(session: CodingSession, task: SessionTask, scopeOwner: String?) {
            if (scopeOwner == null) this@SessionTreeRuntime.start(session, task) else startInScope(session, task, scopeOwner)
        }
        override suspend fun stop(sessionIds: Set<String>) = this@SessionTreeRuntime.stop(sessionIds)
        override suspend fun await(sessionIds: Set<String>) = waitFor(sessionIds)
    }
    val sources = object : SessionOrganismSourceAccess {
        override suspend fun canCreateCodeChild(session: CodingSession) = codingWorkspaces != null
        override suspend fun project(session: CodingSession, project: CodingProject) = sourceProject(session, project)
        override suspend fun lease(session: CodingSession, project: CodingProject): WorkspaceLease? = lock.withLock {
            val handle = handles[session.id]?.takeIf { it.generation == session.runtimeGeneration } ?: return@withLock null
            handle.workspace?.takeIf { it.owner.path == project.path }?.workspaceLease
                ?: handle.directRootLease?.takeIf { it.owner.path == project.path }?.workspaceLease
        }
        override suspend fun inspect(result: SessionResult): String? {
            if (result.commitSha.isNotBlank()) return codingWorkspaces?.inspect(result)
            val project = projects.all().firstOrNull { project -> projects.sessions(project.id).any { it.id == result.sessionId } } ?: return null
            return planningWorkspace?.verificationSnapshot(project.path)
        }
    }

    suspend fun <T> withScope(session: CodingSession, block: suspend (CodingSession) -> T): T = coroutineScope {
        val childScope = SupervisorJob(currentCoroutineContext()[Job])
        val handle = Handle(currentCoroutineContext().job, childScope, generation = session.runtimeGeneration)
        var registered = false
        var current = session
        var failure: Throwable? = null
        try {
            lock.withLock {
                check(!closing) { "Приложение останавливается" }
                require(session.id !in handles) { "Сессия уже работает" }
                handles[session.id] = handle
                registered = true
            }
            current = prepareSession(session)
            val saved = projects.sessions(current.projectId).firstOrNull { it.id == current.id }
            val requestedGeneration = currentCoroutineContext()[RunCapture]?.generation
                ?: session.runtimeGeneration.takeIf { session.stageId != null && it > 0 }
            if (requestedGeneration != null) require((saved?.runtimeGeneration ?: current.runtimeGeneration) == requestedGeneration) {
                "Отложенный запуск другого поколения отозван"
            }
            current = current.copy(organismId = saved?.organismId ?: current.organismId,
                runtimeGeneration = saved?.runtimeGeneration ?: current.runtimeGeneration,
                planningRulesSnapshot = saved?.planningRulesSnapshot ?: current.planningRulesSnapshot)
            if (saved != null && current.organismId == null && session.stageId == null) {
                val adopted = organisms.ensure(current)
                current = current.copy(organismId = adopted.id, runtimeGeneration = adopted.sessions.getValue(current.id).generation)
            }
            if (current.organismId != null) {
                organisms.synchronizeLimits(current.organismId!!)
                val interrupted = organisms.store.get(current.organismId!!).sessions.getValue(session.id)
                if (interrupted.observed == SessionObservedState.UNKNOWN && interrupted.legacyAttempt == null) {
                    requireNotNull(runtime) { "Движок недоступен для сверки" }.reconcileDecided(session.id)
                    organisms.store.reconcileInterruptedRun(current.organismId!!, session.id, interrupted.generation, interrupted.version)
                    // Сверка подтвердила остановку прежнего поколения: его удержанная папка больше не занята.
                    releaseRetainedRootLeases(session.id)
                }
                val node = organisms.store.beginRun(current.organismId!!, session.id)
                current = current.copy(runtimeGeneration = node.generation, planningRulesSnapshot = node.rules)
                handle.generation = current.runtimeGeneration
                currentCoroutineContext()[RunCapture]?.let { it.generation = current.runtimeGeneration; it.entered = true }
                organisms.project(organisms.store.get(current.organismId!!))
            } else {
                current = current.copy(planningRulesSnapshot = current.planningRulesSnapshot ?: settings.load().planningRules.snapshot())
                handle.generation = current.runtimeGeneration
                currentCoroutineContext()[RunCapture]?.let { it.generation = current.runtimeGeneration; it.entered = true }
            }
            // Повторный запуск повторяет и освобождение своего удержания: неудачное восстановление
            // оставляет узел FAILED, и без повтора его устаревшее удержание блокировало бы папку
            // бесконечно — и эту сессию, и соседние. Возврат к удержанию возможен только через сверку движка.
            releaseRetainedRootLeases(session.id)
            suspend fun execute(): T {
                val node = current.organismId?.let { organisms.store.get(it).sessions[current.id] }
                if (node?.kind == SessionKind.SESSION && node.mode == CodingInteractionMode.CODE && current.stageId == null) {
                    val project = projects.all().first { it.id == current.projectId }
                    val (source, parentLease) = lock.withLock {
                        val parent = handles[runtimeParents[current.id] ?: current.parentSessionId] ?: error("Рабочая область родителя закрыта")
                        val source = parent.workspace?.let { project.copy(path = it.record.attempt.path) } ?: parent.executionProject ?: project
                        val lease = parent.workspace?.workspaceLease ?: parent.directRootLease?.workspaceLease
                        source to lease
                    }
                    handle.workspace = (codingWorkspaces ?: error("Изоляция рабочей копии недоступна"))
                        .open(source, current, node.task ?: error("Задание не сохранено"), parentLease)
                    handle.report = currentCoroutineContext()[RunCapture]?.report ?: { "" }
                }
                val result = block(current)
                lock.withLock { handle.closing = true }
                if (handle.failed) childScope.cancel() else childScope.complete()
                childScope.join()
                return result
            }
            if (current.organismId == null) execute() else withConfiguredLimits(current.organismId!!) { execute() }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            withContext(NonCancellable) {
                try {
                    childScope.cancelAndJoin()
                    if (registered) finishScope(current, handle, failure)
                } catch (cleanup: Exception) {
                    if (failure == null) throw cleanup
                    retainCleanupFailure(failure, cleanup)
                    organisms.executionFailed(current.id, "scope_cleanup", cleanup)
                } finally {
                    if (registered) lock.withLock { if (handles[session.id] === handle) handles.remove(session.id) }
                }
            }
        }
    }

    /** A live policy observer allows removing/increasing a deadline without an old timer
     * cancelling the run. No timer or token grant imposes a ceiling by default. */
    private suspend fun <T> withConfiguredLimits(organismId: String, block: suspend () -> T): T = coroutineScope {
        // Keep the body in this scope: callers may emit into their own Flow collector.
        val work = currentCoroutineContext().job
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
            organisms.store.organisms.collectLatest {
                val organism = organisms.store.organisms.value[organismId] ?: return@collectLatest
                if (organism.tokensExhausted()) {
                    work.cancel(CancellationException("Бюджет задачи исчерпан"))
                    return@collectLatest
                }
                val duration = organism.limits.durationMillis ?: return@collectLatest
                delay((duration - (clock() - organism.createdAt).coerceAtLeast(0)).coerceAtLeast(0))
                val latest = organisms.store.organisms.value[organismId] ?: return@collectLatest
                if (latest.limits.durationMillis?.let { clock() - latest.createdAt >= it } == true)
                    work.cancel(CancellationException("Время задачи исчерпано"))
            }
        }
        try { ensureActive(); block() } finally { monitor.cancel() }
    }

    /** Every recorded attempt's process is proven to have exited; what the interrupted turn executed may still be unknown. */
    private val NativeRunRecoverySnapshot.exitProven: Boolean
        get() = !persistenceUnknown && items.all { it.termination == NativeRunTermination.STOPPED }

    private fun SessionOrganism.tokensExhausted(): Boolean =
        limits.tokens?.let { limit -> sessions.values.sumOf { it.spentTokens } >= limit } == true

    private suspend fun finishScope(session: CodingSession, handle: Handle, failure: Throwable?) {
        val cleanup = CleanupFailures(failure)
        try {
            var uncertain = false
            // Stopping proves cleanup, not the outcome of the commands the turn executed. That outcome keeps the folders held
            // until someone decides about it, and it is the native recovery's and the coding run's to resolve.
            var outcomeUnknown: NativeRunRecoveryRequired? = null
            try { cancelQuestions(session.id) } catch (error: Exception) { uncertain = true; cleanup.record(session.id, "questions_revoke", error) }
            // Cancelling a coroutine is not proof that its native process or tool stopped.
            try { runtime.reconcileDecided(session.id) }
            catch (recovery: NativeRunRecoveryRequired) {
                if (recovery.recovery.exitProven) outcomeUnknown = recovery
                else { uncertain = true; cleanup.record(session.id, "native_reconcile", recovery) }
            }
            catch (error: Exception) { uncertain = true; cleanup.record(session.id, "native_reconcile", error) }
            handle.workspace?.let { lease ->
                try {
                    if (uncertain || outcomeUnknown != null) codingWorkspaces!!.quarantine(lease)
                    else codingWorkspaces!!.finish(lease, failure == null && !handle.failed, session.name + "\n" + handle.report())
                } catch (error: Exception) { cleanup.record(session.id, "workspace_finish", error); uncertain = true }
            }
            handle.directRootLease?.let { lease ->
                try {
                    if (uncertain || outcomeUnknown != null) lock.withLock { retainedRootLeases[lease.owner.id] = lease }
                    else planningWorkspace!!.release(lease.workspaceLease)
                } catch (error: Exception) { cleanup.record(session.id, "root_lease_release", error);
                    uncertain = true
                    lock.withLock { retainedRootLeases[lease.owner.id] = lease }
                }
            }
            // Without this generation's node nobody asked this run to stop: an unknown outcome is reported as it was found.
            fun reportUnknownOutcome() { outcomeUnknown?.let { cleanup.record(session.id, "native_reconcile", it) } }
            val organism = session.organismId?.let { organisms.store.get(it) }
                ?: organisms.store.organisms.value.values.firstOrNull { session.id in it.sessions } ?: run { reportUnknownOutcome(); return }
            val node = organisms.store.get(organism.id).sessions.getValue(session.id)
            if (node.generation != session.runtimeGeneration) { reportUnknownOutcome(); return }
            if (node.kind == SessionKind.ZYGOTE && (failure != null || handle.failed)) {
                try { organisms.store.requestFailureStop(organism.id, session.id, session.runtimeGeneration, "Рабочая область корня завершилась с ошибкой или отменой") }
                catch (error: Exception) { cleanup.record(session.id, "failure_stop_checkpoint", error); uncertain = true }
                var controllerConfirmed = false
                try { controllerConfirmed = externalPlanStop(session.id, handle.owner); if (!controllerConfirmed) uncertain = true }
                catch (error: Exception) { cleanup.record(session.id, "plan_stop", error); uncertain = true }
                // Legacy scheduler workers are registered handles but may not be children of
                // this model coroutine. Persist revocation before joining those external jobs.
                val descendants = organisms.store.get(organism.id).subtree(session.id) - session.id
                if (descendants.isNotEmpty()) try { stop(descendants) } catch (error: Exception) { cleanup.record(session.id, "descendants_stop", error); uncertain = true }
                if (controllerConfirmed) try { organisms.project(organisms.store.finishStop(organism.id, descendants)) }
                    catch (error: Exception) { cleanup.record(session.id, "stop_checkpoint", error); uncertain = true }
            }
            // A stop someone asked for is settled by a proven exit; any other unknown outcome leaves the session unknown.
            val requestedStop = node.desired in setOf(SessionDesiredState.STOP, SessionDesiredState.PAUSE)
            if (outcomeUnknown != null) {
                if (requestedStop) AppLog.info("organism", "stop.outcome_unknown", mapOf("sessionId" to session.id, "result" to "exit_proven"))
                else { uncertain = true; reportUnknownOutcome() }
            }
            val observed = when {
                uncertain || node.observed == SessionObservedState.UNKNOWN -> SessionObservedState.UNKNOWN
                failure is CancellationException -> SessionObservedState.STOPPED
                failure != null || handle.failed -> SessionObservedState.FAILED
                node.desired != SessionDesiredState.RUN -> SessionObservedState.STOPPED
                session.stageId != null || node.kind == SessionKind.IMMUNITY || (node.kind == SessionKind.ZYGOTE && session.planningMode) -> SessionObservedState.PENDING
                else -> SessionObservedState.COMPLETED
            }
            val updated = try { organisms.store.observe(organism.id, session.id, node.generation, observed) }
            catch (error: Exception) { cleanup.record(session.id, "scope_outcome", error); organisms.store.observe(organism.id, session.id, node.generation, SessionObservedState.UNKNOWN) }
            organisms.project(updated)
            if (observed == SessionObservedState.FAILED) {
                val siblings = updated.sessions.values.filter { it.id != session.id && it.observed == SessionObservedState.STOPPING &&
                    it.lifecycleParentId == node.lifecycleParentId }.flatMap { updated.subtree(it.id) }.toSet()
                if (siblings.isNotEmpty()) stop(siblings)
            }
        } finally { cleanup.finish() }
    }

    /** Charge only actual token observations; absent provider usage cannot become a hard cost cap. */
    suspend fun observeUsage(session: CodingSession, event: CodingEvent.UsageObserved) {
        val id = session.organismId ?: return
        val total = event.tokens.totalTokens?.coerceAtLeast(0) ?: return
        if (!event.accounting || event.sourceId.isBlank()) return
        // This observation already describes incurred work. A concurrently changed limit
        // may cancel the owner while we wait for policy/accounting locks; keep its cost.
        // Generation checks still reject a late observation after runtime ownership changes.
        val exhausted = withContext(NonCancellable) {
            organisms.synchronizeLimits(id)
            lock.withLock {
                val handle = handles[session.id] ?: throw CancellationException("Рабочая область закрыта")
                require(handle.generation == session.runtimeGeneration) { "Поздний результат другого поколения" }
                val auxiliary = handle.auxiliary
                if (auxiliary != null) {
                    organisms.store.chargeAuxiliary(id, auxiliary.id, event.sourceId, total).tokensExhausted()
                } else {
                    val before = handle.usage[event.sourceId] ?: 0
                    if (total <= before) return@withLock false
                    val updated = organisms.store.charge(SessionAuthority(session.projectId, id, session.id,
                        session.runtimeGeneration, session.interactionMode), total - before)
                    handle.usage[event.sourceId] = total
                    updated.tokensExhausted()
                }
            }
        }
        if (exhausted) {
            try { runtime.abort(session.id) } finally { throw CancellationException("Бюджет задачи исчерпан") }
        }
    }

    private suspend fun start(session: CodingSession, task: SessionTask) = startInScope(session, task, session.parentSessionId ?: error("Родитель не задан"))

    private suspend fun startInScope(session: CodingSession, task: SessionTask, runtimeId: String) {
        try {
            val engine = runtime
            lock.withLock {
                check(!closing) { "Приложение останавливается" }
                val parent = handles[runtimeId] ?: error("Рабочая область родителя не запущена")
                require(runtimeId == session.parentSessionId || parent.auxiliary?.ownerSessionId == session.parentSessionId) { "Runtime не принадлежит родителю" }
                if (children[session.id]?.isCompleted == false) return
                require(!parent.closing && parent.children.isActive) { "Рабочая область родителя закрывается" }
                runtimeParents[session.id] = runtimeId
                val capture = RunCapture(session.runtimeGeneration)
                val job = CoroutineScope(Dispatchers.Default + parent.children + capture).launch(start = CoroutineStart.LAZY) {
                    val ownerJob = currentCoroutineContext().job
                    val recorder = CodingRunRecorder()
                    capture.report = { recorder.message("workspace-report", clock()).text }
                    var failure: Throwable? = null
                    try {
                        waitFor(task.dependencies)
                        task.dependencies.forEach { dependency ->
                            val node = organisms.store.get(session.organismId!!).sessions.getValue(dependency)
                            require(node.observed == SessionObservedState.COMPLETED) { "Предыдущее задание не завершено успешно" }
                        }
                        val project = projects.all().first { it.id == session.projectId }
                        val roster = profiles.load()
                        val profile = session.modelSelection?.let { ProfileResolver.selection(it, roster) }
                            ?: roster.firstOrNull { it.id == settings.load().activeLlmProfileId }
                        val prompt = "Контекст от сессии ${session.parentSessionId}\n${task.text}\n\nКритерии завершения: ${task.acceptance}"
                        organisms.appendHistory(project.id, session.id, CodingMessage("${session.id}-task-${capture.generation}", CodingRole.USER,
                            prompt, createdAt = clock(), origin = MessageOrigin.SESSION))
                        val events = if (session.planningMode) engine.runPlanning(project, session, prompt, profile ?: error("Профиль недоступен"))
                            else engine.run(project, session, prompt, profile)
                        events.collect { recorder.apply(it) }
                    } catch (error: CancellationException) {
                        failure = error
                        recorder.apply(CodingEvent.Failed("Выполнение остановлено"))
                        throw error
                    } catch (error: Exception) {
                        failure = error
                        AppLog.error("organism", "child_failed", mapOf("sessionId" to session.id, "causeType" to error::class.simpleName.orEmpty()))
                        recorder.apply(CodingEvent.Failed("Не удалось выполнить дочернюю сессию"))
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                val id = session.organismId
                                if (id != null) {
                                    val node = organisms.store.get(id).sessions.getValue(session.id)
                                    if (node.generation == capture.generation) {
                                        if (!capture.entered) {
                                            val observed = if (failure is CancellationException) SessionObservedState.STOPPED else SessionObservedState.FAILED
                                            val updated = organisms.store.observe(id, session.id, capture.generation, observed)
                                            organisms.project(updated)
                                            val siblings = updated.sessions.values.filter { it.id != session.id &&
                                                it.lifecycleParentId == node.lifecycleParentId && it.observed == SessionObservedState.STOPPING }
                                                .flatMap { updated.subtree(it.id) }.toSet()
                                            if (siblings.isNotEmpty()) stop(siblings)
                                        }
                                        val result = recorder.message("${session.id}-result-${capture.generation}", clock())
                                        val aggregate = organisms.store.get(id)
                                        val selected = aggregate.completedResultWorkspace(session.id, capture.generation)
                                        val integration = selected?.first?.let { aggregate.integrations.getValue(it) }
                                        val workspace = selected?.second ?: node.workspace?.takeIf { it.generation == capture.generation }
                                        val captured = workspace?.takeIf { it.phase == SessionCodingWorkspacePhase.CAPTURED }
                                        // The aggregate checks captured generation before any history projection.
                                        organisms.store.recordResult(id, SessionResult(result.id, session.id, capture.generation,
                                            task.resultRecipient, result.text,
                                            artifacts = workspace?.attempt?.path?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
                                            evidence = workspace?.let { listOf("baseCommit=${it.attempt.baseCommit}", "sourceSnapshot=${it.sourceSnapshot.orEmpty()}", "workspacePhase=${it.phase}") }.orEmpty(),
                                            sourceVersion = captured?.resultSnapshot ?: task.sourceVersion, commitSha = captured?.attempt?.resultCommit.orEmpty(),
                                            checks = integration?.resultChecks().orEmpty(), integrationId = integration?.request?.id))
                                        organisms.appendHistory(session.projectId, session.id, result)
                                        organisms.deliver(id)
                                    }
                                }
                            } catch (resultFailure: Exception) {
                                failure?.let { retainCleanupFailure(it, resultFailure) } ?: run { failure = resultFailure }
                                AppLog.error("organism", "child_result_failed", mapOf("sessionId" to session.id, "causeType" to resultFailure::class.simpleName.orEmpty()))
                                // A result/projection failure must not make unfinished task state look accepted.
                                session.organismId?.let { id ->
                                    val node = organisms.store.get(id).sessions.getValue(session.id)
                                    if (node.generation == capture.generation)
                                        organisms.project(organisms.store.observe(id, session.id, capture.generation, SessionObservedState.UNKNOWN))
                                }
                            } finally {
                                // A final response is handed to the parent owner; unknown results
                                // retain its recovery checkpoint and never authorize an automatic retry.
                                try { capture.request?.let { ref ->
                                    val live = projects.states.value[session.projectId]?.run(ref)
                                    if (live?.phase !in setOf(CodingMachine.Phase.RUNNING, CodingMachine.Phase.STOPPING)) return@let
                                    val unknown = session.organismId?.let { id -> organisms.store.get(id).sessions[session.id]?.observed == SessionObservedState.UNKNOWN } == true
                                    try {
                                        val response = recorder.message(ref.responseId, clock()).copy(timelineId = ref.timelineId)
                                        projects.dispatch(session.projectId, CodingMachine.Fact.RunFinished(ref, response, outcomeKnown = !unknown))
                                    } catch (completion: Exception) {
                                        AppLog.error("organism", "child_completion_failed", mapOf("sessionId" to session.id, "causeType" to completion::class.simpleName.orEmpty()))
                                        failure?.let { retainCleanupFailure(it, completion) } ?: throw completion
                                    }
                                }
                                } finally {
                                    lock.withLock { if (children[session.id] === ownerJob) { children.remove(session.id); runtimeParents.remove(session.id) } }
                                }
                            }
                        }
                    }
                }
                children[session.id] = job
                // Admission is durable before a native child may start. The returned identity
                // remains fixed while its organism execution generation is adopted separately.
                val request = CodingRunCheckpoint(messageId = "${session.id}-task-${capture.generation}",
                    prompt = "Контекст от сессии ${session.parentSessionId}\n${task.text}\n\nКритерии завершения: ${task.acceptance}",
                    responseId = "${session.id}-result-${capture.generation}", responseTimelineId = Id.new(),
                    intent = ExecutionIntent.RUN, interactionMode = session.interactionMode)
                val admitted = projects.dispatch(session.projectId, CodingMachine.Intent.BeginRun(
                    CodingMachine.SessionRef(session.id, session.runtimeGeneration), request, clock()))
                capture.request = admitted.effects.filterIsInstance<CodingMachine.Effect.RunRequest>().single().ref
                job.start()
            }
        } catch (failure: Exception) {
            // The CREATE transaction may precede admission. A never-started child must not
            // retain a reservation or prevent a closing parent from settling indefinitely.
            withContext(NonCancellable) {
                val neverStarted = lock.withLock { runtimeParents.remove(session.id); children.remove(session.id) }
                neverStarted?.cancelAndJoin()
                try { recordStopped(session.projectId, session.id, unknown = false) }
                catch (cleanup: Exception) {
                    retainCleanupFailure(failure, cleanup)
                    AppLog.error("organism", "child_admission_cleanup_failed", mapOf("sessionId" to session.id, "causeType" to cleanup::class.simpleName.orEmpty()))
                }
                try {
                    session.organismId?.let { id ->
                        val node = organisms.store.get(id).sessions.getValue(session.id)
                        if (node.generation == session.runtimeGeneration && node.observed == SessionObservedState.PENDING)
                            organisms.project(organisms.store.observe(id, session.id, node.generation, SessionObservedState.STOPPED))
                    }
                } catch (cleanup: Exception) {
                    retainCleanupFailure(failure, cleanup)
                    AppLog.error("organism", "child_admission_projection_failed", mapOf("sessionId" to session.id, "causeType" to cleanup::class.simpleName.orEmpty()))
                }
            }
            throw failure
        }
    }

    private suspend fun waitFor(ids: Set<String>) {
        val jobs = lock.withLock { ids.mapNotNull { children[it] } }
        jobs.joinAll()
        ids.forEach { id ->
            val organism = organisms.store.organisms.value.values.firstOrNull { id in it.sessions } ?: error("Сессия не найдена")
            require(organisms.store.get(organism.id).sessions.getValue(id).settled) { "Выполнение ещё не подтверждено" }
        }
    }

    private suspend fun stop(ids: Set<String>) = withContext(NonCancellable) {
        val cleanup = CleanupFailures()
        try {
            val owned = lock.withLock {
                val aliases = handles.filterValues { it.auxiliary?.ownerSessionId in ids }.keys
                (ids + aliases).mapNotNull { id -> (children[id] ?: handles[id]?.owner)?.let { id to it } }
            }
            // First revoke every coroutine. Failure to persist one question cannot leave a sibling alive.
            owned.forEach { (_, job) -> job.cancel() }
            owned.forEach { (id, _) ->
                try { cancelQuestions(id) } catch (error: Exception) { cleanup.record(id, "questions_revoke", error) }
                try { runtime.abort(id) } catch (error: Exception) { cleanup.record(id, "native_abort", error) }
            }
            owned.map { it.second }.joinAll()
            val activeIds = owned.map { it.first }.toSet()
            organisms.store.organisms.value.values.forEach { organism ->
                organism.auxiliaryRuns.values.filter { it.ownerSessionId in ids && !it.settled && it.sessionId !in activeIds }.forEach { run ->
                    val observed = try { cancelQuestions(run.sessionId); runtime.reconcileDecided(run.sessionId); SessionObservedState.STOPPED }
                        catch (recovery: NativeRunRecoveryRequired) {
                            cleanup.record(run.sessionId, "auxiliary_reconcile", recovery)
                            if (organism.sessions[run.ownerSessionId]?.desired == SessionDesiredState.STOP) SessionObservedState.STOPPED else SessionObservedState.UNKNOWN
                        }
                        catch (error: Exception) { cleanup.record(run.sessionId, "auxiliary_reconcile", error); SessionObservedState.UNKNOWN }
                    organisms.project(organisms.store.finishAuxiliary(organism.id, run.id, observed))
                }
            }
            // A pending child has no native job. Reconcile even when process ownership is only on disk.
            val known = ids.mapNotNull { id -> organisms.store.organisms.value.values.firstOrNull { id in it.sessions }?.let { id to it } }
            known.sortedByDescending { (id, organism) ->
                var depth = 0
                var parent = organism.sessions[id]?.lifecycleParentId
                while (parent != null) { depth++; parent = organism.sessions[parent]?.lifecycleParentId }
                depth
            }.forEach { (id, organism) ->
                val node = organisms.store.get(organism.id).sessions.getValue(id)
                if (!node.settled && id !in activeIds) {
                    val observed = try {
                        cancelQuestions(id)
                        runtime.reconcileDecided(id)
                        codingWorkspaces?.releaseStopped(id)
                        val leases = lock.withLock { retainedRootLeases.values.filter { it.sessionId == id } }
                        leases.forEach { lease ->
                            planningWorkspace!!.release(lease.workspaceLease)
                            lock.withLock { if (retainedRootLeases[lease.owner.id] === lease) retainedRootLeases.remove(lease.owner.id) }
                        }
                        SessionObservedState.STOPPED
                    }
                        catch (recovery: NativeRunRecoveryRequired) {
                            cleanup.record(id, "session_reconcile", recovery)
                            if (node.desired == SessionDesiredState.STOP) SessionObservedState.STOPPED else SessionObservedState.UNKNOWN
                        }
                        catch (error: Exception) { cleanup.record(id, "session_reconcile", error); SessionObservedState.UNKNOWN }
                    // A stopped child must not be picked up again by restoreCodingRuns.
                    recordStopped(organism.projectId, id, unknown = observed == SessionObservedState.UNKNOWN)
                    organisms.project(organisms.store.observe(organism.id, id, node.generation, observed))
                }
            }
        } finally { cleanup.finish() }
    }

    suspend fun requireTaskQuiescent(sessionId: String) {
        val organism = organisms.store.organisms.value.values.firstOrNull { sessionId in it.sessions } ?: return
        val writers = organism.sessions.values.filter { it.mode == CodingInteractionMode.CODE }.map { it.id }.toSet()
        lock.withLock {
            check(handles.keys.none { it in writers } && retainedRootLeases.values.none { it.sessionId in writers }) { "Остановка исполнителей не подтверждена" }
        }
        check(codingWorkspaces?.retainedSessionIds().orEmpty().none { it in writers }) { "Рабочая копия исполнителя ещё занята" }
        check(organism.sessions.values.none { it.id in writers && it.observed in setOf(SessionObservedState.RUNNING,
            SessionObservedState.WAITING_USER, SessionObservedState.STOPPING, SessionObservedState.UNKNOWN) }) { "Не все исполнители завершили работу" }
    }

    suspend fun pauseForReset() {
        shutdown()
        val unsettled = organisms.store.loadAll().flatMap { it.sessions.values }
            .filter { it.observed in setOf(SessionObservedState.RUNNING, SessionObservedState.WAITING_USER,
                SessionObservedState.STOPPING, SessionObservedState.UNKNOWN) }.map { it.id }.toSet()
        stop(unsettled)
        check(organisms.store.organisms.value.values.flatMap { it.sessions.values }
            .none { it.id in unsettled && !it.settled }) { "Не удалось подтвердить остановку сессий" }
        check(codingWorkspaces?.retainedSessionIds().orEmpty().isEmpty()) { "Не удалось освободить рабочие области" }
        lock.withLock { check(retainedRootLeases.isEmpty()) { "Не удалось освободить проекты" } }
    }

    suspend fun resumeAfterReset() = lock.withLock {
        check(handles.isEmpty() && children.values.none { it.isActive }) { "Дождитесь остановки сессий" }
        children.clear()
        runtimeParents.clear()
        closing = false
    }

    suspend fun shutdown() {
        val workspaceIds = codingWorkspaces?.retainedSessionIds().orEmpty()
        val persistedAliases = organisms.store.organisms.value.values.flatMap { it.auxiliaryRuns.values.filter { run -> !run.settled }.map { run -> run.ownerSessionId } }
        val ids = lock.withLock { closing = true; (handles.keys + children.keys + retainedRootLeases.values.map { it.sessionId } + workspaceIds + persistedAliases).toSet() }
        stop(ids)
    }

    private companion object {
        const val ROOT_LEASE_POLL_MILLIS = 250L
        const val WORKING_FOLDER = "Рабочая папка"
    }
}
