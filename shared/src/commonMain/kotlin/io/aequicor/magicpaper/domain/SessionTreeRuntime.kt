package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.Id
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
    private val projects: CodingProjectRepository,
    private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long = Id::now,
    private val planningWorkspace: PlanningWorkspace? = null,
) {
    private data class RootLease(val sessionId: String, val generation: Long, val owner: CodingProject)
    private data class Handle(val owner: Job, val children: CompletableJob, var failed: Boolean = false,
        var generation: Long = 0, var closing: Boolean = false, val usage: MutableMap<String, Long> = mutableMapOf(),
        var workspace: SessionCodingWorkspaces.Lease? = null, var directRootLease: RootLease? = null,
        var auxiliary: SessionAuxiliaryRun? = null,
        var executionProject: CodingProject? = null, var report: () -> String = { "" })
    /** Captured at beginRun, never replaced by a newer generation when an old producer finishes. */
    private class RunCapture(var generation: Long) : AbstractCoroutineContextElement(Key) {
        var entered = false
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
    var runtime: CodingRuntime? = null
    var cancelQuestions: suspend (String) -> Unit = {}
    /** The owning planner admits a confirmed stage before this runtime receives authority. */
    var prepareSession: suspend (CodingSession) -> CodingSession = { it }
    /** Controller closure may be deferred when the failed callback belongs to that controller. */
    var externalPlanStop: suspend (String, Job) -> Boolean = { _, _ -> true }
    suspend fun failed(sessionId: String) { lock.withLock { handles[sessionId]?.failed = true } }
    suspend fun incoming(session: CodingSession): List<SessionDelivery> = organisms.pendingContext(session)
    suspend fun processed(session: CodingSession, ids: List<String>) = organisms.acknowledgeContext(session, ids)
    suspend fun auxiliaryContext(context: ToolExecutionContext): ToolExecutionContext {
        if (context.organismId != null) {
            organisms.synchronizeLimits(context.organismId)
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
            val auxiliary = organisms.store.beginAuxiliary(bound)
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
            try {
                children.cancelAndJoin()
                var uncertain = false
                try { cancelQuestions(session.id) } catch (_: Exception) { uncertain = true }
                try { runtime?.reconcile(session.id) } catch (_: Exception) { uncertain = true }
                handle.auxiliary?.let { run ->
                    val observed = when { uncertain -> SessionObservedState.UNKNOWN; failure is CancellationException -> SessionObservedState.STOPPED
                        failure != null || handle.failed -> SessionObservedState.FAILED; else -> SessionObservedState.COMPLETED }
                    organisms.project(organisms.store.finishAuxiliary(organismId, run.id, observed))
                }
            } finally { if (registered) lock.withLock { if (handles[session.id] === handle) handles.remove(session.id) } }
        } }
    }
    suspend fun projectForExecution(project: CodingProject, session: CodingSession): CodingProject = lock.withLock {
        val handle = handles[session.id] ?: error("Рабочая область не запущена")
        require(handle.generation == session.runtimeGeneration) { "Рабочая область другого поколения" }
        val node = session.organismId?.let { organisms.store.get(it).sessions[session.id] }
        if (planningWorkspace != null && node?.kind == SessionKind.ZYGOTE && session.interactionMode == CodingInteractionMode.CODE &&
            session.planId == null && session.stageId == null && handle.directRootLease == null) {
            // Each session acquires its own workspace lease by unique owner ID;
            // concurrent sessions in the same project are independent.
            val owner = project.copy(id = "root-${session.id}-${session.runtimeGeneration}")
            withContext(NonCancellable) {
                check(planningWorkspace.acquire(owner)) { "Рабочая копия уже используется другой сессией" }
                handle.directRootLease = RootLease(session.id, session.runtimeGeneration, owner)
            }
        }
        (handle.workspace?.let { project.copy(path = it.record.attempt.path) } ?: project).also { handle.executionProject = it }
    }
    suspend fun sourceProject(session: CodingSession, project: CodingProject): CodingProject = lock.withLock {
        val handle = handles[session.id] ?: error("Рабочая область не запущена")
        require(handle.generation == session.runtimeGeneration) { "Рабочая область другого поколения" }
        handle.workspace?.let { project.copy(path = it.record.attempt.path) } ?: handle.executionProject ?: project
    }

    init {
        organisms.startChild = ::start
        organisms.startChildInScope = ::startInScope
        organisms.waitChildren = ::waitFor
        organisms.stopSubtree = ::stop
        organisms.canCreateCodeChild = { codingWorkspaces != null }
        organisms.sourceProject = ::sourceProject
        organisms.sourceLeaseHeld = { session, project -> lock.withLock {
            handles[session.id]?.let { handle -> handle.generation == session.runtimeGeneration &&
                (handle.workspace?.owner?.path == project.path || handle.directRootLease?.owner?.path == project.path) } == true
        } }
        val inspectOtherResult = organisms.inspectResult
        organisms.inspectResult = { result ->
            if (result.commitSha.isNotBlank()) codingWorkspaces?.inspect(result) else inspectOtherResult(result)
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
                val node = organisms.store.beginRun(current.organismId!!, session.id)
                current = current.copy(runtimeGeneration = node.generation, planningRulesSnapshot = node.rules)
                handle.generation = current.runtimeGeneration
                currentCoroutineContext()[RunCapture]?.let { it.generation = current.runtimeGeneration; it.entered = true }
                organisms.project(organisms.store.get(current.organismId!!))
                // After a crash, children that were RUNNING are now UNKNOWN.
                // Restart them here so workspace isolation and runtime parents are correct.
                recoverUnknownChildren(current)
            } else {
                current = current.copy(planningRulesSnapshot = current.planningRulesSnapshot ?: settings.load().planningRules.snapshot())
                handle.generation = current.runtimeGeneration
                currentCoroutineContext()[RunCapture]?.let { it.generation = current.runtimeGeneration; it.entered = true }
            }
            suspend fun execute(): T {
                val node = current.organismId?.let { organisms.store.get(it).sessions[current.id] }
                if (node?.kind == SessionKind.SESSION && node.mode == CodingInteractionMode.CODE && current.stageId == null) {
                    val project = projects.all().first { it.id == current.projectId }
                    val source = lock.withLock {
                        val parent = handles[runtimeParents[current.id] ?: current.parentSessionId] ?: error("Рабочая область родителя закрыта")
                        parent.workspace?.let { project.copy(path = it.record.attempt.path) } ?: parent.executionProject ?: project
                    }
                    val parentOwnsSource = lock.withLock { handles[runtimeParents[current.id] ?: current.parentSessionId]?.let { parent ->
                        parent.workspace?.owner?.path == source.path || parent.directRootLease?.owner?.path == source.path
                    } == true }
                    handle.workspace = (codingWorkspaces ?: error("Изоляция рабочей копии недоступна"))
                        .open(source, current, node.task ?: error("Задание не сохранено"), parentOwnsSource)
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

    private fun SessionOrganism.tokensExhausted(): Boolean =
        limits.tokens?.let { limit -> sessions.values.sumOf { it.spentTokens } >= limit } == true

    private suspend fun finishScope(session: CodingSession, handle: Handle, failure: Throwable?) {
        var uncertain = false
        try { cancelQuestions(session.id) } catch (_: Exception) { uncertain = true }
        // Cancelling a coroutine is not proof that its native process or tool stopped.
        try { runtime?.reconcile(session.id) } catch (_: Exception) { uncertain = true }
        handle.workspace?.let { lease ->
            try {
                if (uncertain) codingWorkspaces!!.quarantine(lease)
                else codingWorkspaces!!.finish(lease, failure == null && !handle.failed, session.name + "\n" + handle.report())
            } catch (_: Exception) { uncertain = true }
        }
        handle.directRootLease?.let { lease ->
            try {
                if (uncertain) lock.withLock { retainedRootLeases[lease.owner.id] = lease }
                else planningWorkspace!!.release(lease.owner)
            } catch (_: Exception) {
                uncertain = true
                lock.withLock { retainedRootLeases[lease.owner.id] = lease }
            }
        }
        val organism = session.organismId?.let { organisms.store.get(it) }
            ?: organisms.store.organisms.value.values.firstOrNull { session.id in it.sessions } ?: return
        val node = organisms.store.get(organism.id).sessions.getValue(session.id)
        if (node.generation != session.runtimeGeneration) return
        if (node.kind == SessionKind.ZYGOTE && (failure != null || handle.failed)) {
            try { organisms.store.requestFailureStop(organism.id, session.id, session.runtimeGeneration, "Рабочая область корня завершилась с ошибкой или отменой") }
            catch (_: Exception) { uncertain = true }
            var controllerConfirmed = false
            try { controllerConfirmed = externalPlanStop(session.id, handle.owner); if (!controllerConfirmed) uncertain = true }
            catch (_: Exception) { uncertain = true }
            // Legacy scheduler workers are registered handles but may not be children of
            // this model coroutine. Persist revocation before joining those external jobs.
            val descendants = organisms.store.get(organism.id).subtree(session.id) - session.id
            if (descendants.isNotEmpty()) try { stop(descendants) } catch (_: Exception) { uncertain = true }
            if (controllerConfirmed) try { organisms.project(organisms.store.finishStop(organism.id, descendants)) }
                catch (_: Exception) { uncertain = true }
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
        catch (_: Exception) { organisms.store.observe(organism.id, session.id, node.generation, SessionObservedState.UNKNOWN) }
        organisms.project(updated)
        if (observed == SessionObservedState.FAILED) {
            val siblings = updated.sessions.values.filter { it.id != session.id && it.observed == SessionObservedState.STOPPING &&
                it.lifecycleParentId == node.lifecycleParentId }.flatMap { updated.subtree(it.id) }.toSet()
            if (siblings.isNotEmpty()) stop(siblings)
        }
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
            try { runtime?.abort(session.id) } finally { throw CancellationException("Бюджет задачи исчерпан") }
        }
    }

    private suspend fun start(session: CodingSession, task: SessionTask) = startInScope(session, task, session.parentSessionId ?: error("Родитель не задан"))

    private suspend fun startInScope(session: CodingSession, task: SessionTask, runtimeId: String) {
        try {
            val engine = runtime ?: error("Runtime не подключён")
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
                        recorder.apply(CodingEvent.Failed(error.message ?: "Ошибка дочерней сессии"))
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
                            } catch (_: Exception) {
                                // A result/projection failure must not make unfinished task state look accepted.
                                session.organismId?.let { id ->
                                    val node = organisms.store.get(id).sessions.getValue(session.id)
                                    if (node.generation == capture.generation)
                                        organisms.project(organisms.store.observe(id, session.id, capture.generation, SessionObservedState.UNKNOWN))
                                }
                            } finally {
                                // Clear the recovery checkpoint so restoreCodingRuns skips completed/failed work.
                                // A crash before this line leaves the checkpoint on disk — that is the intended signal.
                                runCatching { projects.updateSession(session.projectId, session.id) { it.copy(pendingRun = null) } }
                                lock.withLock { if (children[session.id] === ownerJob) { children.remove(session.id); runtimeParents.remove(session.id) } }
                            }
                        }
                    }
                }
                children[session.id] = job
                // Persist a recovery checkpoint so restoreCodingRuns can resume this child after a crash.
                // A crash before this point means no recovery; after it the checkpoint survives until the run ends.
                runCatching {
                    projects.updateSession(session.projectId, session.id) {
                        it.copy(pendingRun = CodingRunCheckpoint(
                            messageId = "${session.id}-task-${capture.generation}",
                            prompt = "Контекст от сессии ${session.parentSessionId}\n${task.text}\n\nКритерии завершения: ${task.acceptance}",
                            intent = ExecutionIntent.RUN,
                            interactionMode = session.interactionMode,
                        ))
                    }
                }
                job.start()
            }
        } catch (failure: Exception) {
            // The CREATE transaction may precede admission. A never-started child must not
            // retain a reservation or prevent a closing parent from settling indefinitely.
            withContext(NonCancellable) {
                // Clear the recovery checkpoint if it was saved before the job could start.
                runCatching { projects.updateSession(session.projectId, session.id) { it.copy(pendingRun = null) } }
                session.organismId?.let { id ->
                    val node = organisms.store.get(id).sessions.getValue(session.id)
                    if (node.generation == session.runtimeGeneration && node.observed == SessionObservedState.PENDING)
                        organisms.project(organisms.store.observe(id, session.id, node.generation, SessionObservedState.STOPPED))
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

    /** Restart UNKNOWN children whose checkpoint survived a crash. Called after beginRun
     *  so the parent handle is already registered and workspace isolation works correctly. */
    private suspend fun recoverUnknownChildren(parent: CodingSession) {
        val organismId = parent.organismId ?: return
        val organism = organisms.store.get(organismId)
        val candidates = organism.sessions.values.filter { node ->
            node.authorityParentId == parent.id && node.kind == SessionKind.SESSION &&
                node.observed == SessionObservedState.UNKNOWN && node.desired == SessionDesiredState.RUN &&
                !node.archived && !node.settled && node.task != null
        }
        for (child in candidates) {
            val session = projects.sessions(parent.projectId).firstOrNull { it.id == child.id } ?: continue
            val pendingRun = session.pendingRun ?: continue
            if (pendingRun.intent != ExecutionIntent.RUN || session.planningMode || session.stageId != null) continue
            // Clear the checkpoint so restoreCodingRuns does not duplicate this recovery.
            runCatching { projects.updateSession(parent.projectId, child.id) { it.copy(pendingRun = null) } }
            try {
                startInScope(session.copy(runtimeGeneration = child.generation), child.task!!, parent.id)
            } catch (_: Exception) {
                // Recovery failure keeps the child UNKNOWN; the user can retry manually.
            }
        }
    }

    private suspend fun stop(ids: Set<String>) = withContext(NonCancellable) {
        val owned = lock.withLock {
            val aliases = handles.filterValues { it.auxiliary?.ownerSessionId in ids }.keys
            (ids + aliases).mapNotNull { id -> (children[id] ?: handles[id]?.owner)?.let { id to it } }
        }
        // First revoke every coroutine. Failure to persist one question cannot leave a sibling alive.
        owned.forEach { (_, job) -> job.cancel() }
        owned.forEach { (id, _) ->
            runCatching { cancelQuestions(id) }
            runCatching { runtime?.abort(id) }
        }
        owned.map { it.second }.joinAll()
        val activeIds = owned.map { it.first }.toSet()
        organisms.store.organisms.value.values.forEach { organism ->
            organism.auxiliaryRuns.values.filter { it.ownerSessionId in ids && !it.settled && it.sessionId !in activeIds }.forEach { run ->
                val observed = try { cancelQuestions(run.sessionId); runtime?.reconcile(run.sessionId); SessionObservedState.STOPPED }
                    catch (_: Exception) { SessionObservedState.UNKNOWN }
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
                    runtime?.reconcile(id)
                    codingWorkspaces?.releaseStopped(id)
                    val leases = lock.withLock { retainedRootLeases.values.filter { it.sessionId == id } }
                    leases.forEach { lease ->
                        planningWorkspace!!.release(lease.owner)
                        lock.withLock { if (retainedRootLeases[lease.owner.id] === lease) retainedRootLeases.remove(lease.owner.id) }
                    }
                    SessionObservedState.STOPPED
                }
                    catch (_: Exception) { SessionObservedState.UNKNOWN }
                // A stopped child must not be picked up again by restoreCodingRuns.
                runCatching { projects.updateSession(organism.projectId, id) { it.copy(pendingRun = null) } }
                organisms.project(organisms.store.observe(organism.id, id, node.generation, observed))
            }
        }
    }

    suspend fun shutdown() {
        val workspaceIds = codingWorkspaces?.retainedSessionIds().orEmpty()
        val persistedAliases = organisms.store.organisms.value.values.flatMap { it.auxiliaryRuns.values.filter { run -> !run.settled }.map { run -> run.ownerSessionId } }
        val ids = lock.withLock { closing = true; (handles.keys + children.keys + retainedRootLeases.values.map { it.sessionId } + workspaceIds + persistedAliases).toSet() }
        stop(ids)
    }
}
