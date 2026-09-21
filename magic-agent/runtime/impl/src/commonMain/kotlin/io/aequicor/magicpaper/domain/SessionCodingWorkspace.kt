package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Reuses the planner's worktrees, private-index commits and writer leases. Never applies to the checkout. */
internal class SessionCodingWorkspaces(
    private val workspaces: PlanningWorkspace,
    private val store: SessionOrganismStore,
) {
    internal data class Lease(val organismId: String, val sessionId: String, val owner: CodingProject,
        var record: SessionCodingWorkspace, val workspaceLease: WorkspaceLease? = null)
    private val preparation = Mutex()
    private val retained = Mutex()
    private val uncertainLeases = mutableMapOf<String, Lease>()
    suspend fun retainedSessionIds(): Set<String> = retained.withLock { uncertainLeases.values.map { it.sessionId }.toSet() }

    private fun operation(lease: Lease, step: String) = WorkspaceOperation(checkNotNull(lease.workspaceLease),
        "${lease.record.runId}:${lease.record.generation}:$step")

    private suspend fun save(lease: Lease, record: SessionCodingWorkspace) {
        store.recordWorkspace(lease.organismId, lease.sessionId, record.generation, record)
        lease.record = record
    }

    private suspend fun release(lease: Lease) {
        val handle = lease.workspaceLease ?: return
        try { workspaces.release(handle) }
        catch (failure: Throwable) {
            retained.withLock { uncertainLeases[handle.token] = lease }
            throw failure
        }
        retained.withLock { if (uncertainLeases[handle.token]?.workspaceLease == handle) uncertainLeases.remove(handle.token) }
    }

    private suspend fun cleanup(primary: Throwable, sessionId: String, operation: String, action: suspend () -> Unit) {
        try { action() } catch (failure: Throwable) {
            if (failure !== primary) primary.addSuppressed(failure)
            AppLog.error("coding.workspace", "cleanup.failed", fields = mapOf("sessionId" to sessionId,
                "operation" to operation, "causeType" to failure::class.simpleName.orEmpty()))
        }
    }

    suspend fun open(project: CodingProject, session: CodingSession, task: SessionTask, parentSourceLease: WorkspaceLease? = null): Lease = preparation.withLock {
        val organismId = session.organismId ?: error("Организм не сохранён")
        val node = store.get(organismId).sessions.getValue(session.id)
        require(node.generation == session.runtimeGeneration && node.observed == SessionObservedState.RUNNING) { "Запуск рабочей копии не подтверждён" }
        require(node.workspace?.generation != session.runtimeGeneration) { "Рабочая копия запуска уже создана; сначала сверяйте её состояние" }
        val key = "session-${session.id}-${session.runtimeGeneration}"
        val initial = SessionCodingWorkspace(session.runtimeGeneration, key,
            StageAttempt(key, session.id, StageAssignment(session.modelSelection?.profileId.orEmpty(), session.modelSelection?.modelId.orEmpty()),
                engine = session.engine), sourcePath = project.path)
        // Persist identity before prepare/stage can touch Git or the filesystem.
        store.recordWorkspace(organismId, session.id, session.runtimeGeneration, initial)
        var sourceLease: WorkspaceLease? = null
        var executionLease: WorkspaceLease? = null
        var externalStarted = false
        var lease = Lease(organismId, session.id, project.copy(id = "$key-source"), initial)
        val sourceOwner = lease.owner
        var primaryFailure: Throwable? = null
        try {
            // An active managed parent already owns this exact path. Preparation only reads
            // its files through a private index; the child writer gets a different lease.
            if (parentSourceLease == null) withContext(NonCancellable) { sourceLease = workspaces.acquire(lease.owner, Id.new()) }
            check(parentSourceLease != null || sourceLease != null) { "Исходная рабочая копия занята" }
            val sourceHandle = checkNotNull(parentSourceLease ?: sourceLease)
            fun sourceOperation(step: String) = WorkspaceOperation(sourceHandle, "$key:$step")
            currentCoroutineContext().ensureActive()
            val before = workspaces.verificationSnapshot(project.path, sourceOperation("snapshot-before"))
                ?: error("Проверка исходников недоступна")
            require(task.sourceVersion.isBlank() || task.sourceVersion == before) { "Исходники задания изменились; создайте новое задание" }
            externalStarted = true
            val workspace = workspaces.prepare(project, initial.runId, sourceOperation("prepare"))
            require(workspace.git) { "Дочерняя разработка требует Git и отдельной рабочей копии" }
            require(workspaces.verificationSnapshot(project.path, sourceOperation("snapshot-after")) == before) { "Исходники изменились при создании рабочей копии" }
            val attempt = workspaces.stage(project, workspace, initial.attempt, sourceOperation("stage"))
            require(attempt.path.isNotBlank() && attempt.path != project.path && attempt.baseCommit.isNotBlank()) { "Изолированная рабочая копия не подтверждена" }
            val ready = initial.copy(workspace = workspace, attempt = attempt, sourceSnapshot = before, phase = SessionCodingWorkspacePhase.READY)
            save(lease, ready)
            val executionOwner = project.copy(id = key, path = attempt.path)
            withContext(NonCancellable) {
                executionLease = workspaces.acquire(executionOwner, Id.new())
                executionLease?.let { lease = lease.copy(owner = executionOwner, workspaceLease = it) }
            }
            check(executionLease != null) { "Рабочая копия дочерней сессии занята" }
            workspaces.reconcile(attempt, operation(lease, "open-reconcile"))
            currentCoroutineContext().ensureActive()
            save(lease, ready.copy(phase = SessionCodingWorkspacePhase.RUNNING))
            sourceLease?.let { source -> withContext(NonCancellable) {
                release(lease.copy(owner = sourceOwner, workspaceLease = source))
                sourceLease = null
            }
            }
            lease
        } catch (error: Throwable) {
            primaryFailure = error
            withContext(NonCancellable) {
                if (executionLease != null) cleanup(error, session.id, "execution_lease_release") { release(lease) }
                val phase = if (externalStarted) SessionCodingWorkspacePhase.UNKNOWN else SessionCodingWorkspacePhase.STOPPED
                cleanup(error, session.id, "workspace_checkpoint") { save(lease, lease.record.copy(phase = phase,
                    error = "Не удалось подтвердить состояние рабочей копии")) }
                if (externalStarted) cleanup(error, session.id, "session_checkpoint") {
                    store.observe(organismId, session.id, session.runtimeGeneration, SessionObservedState.UNKNOWN)
                }
            }
            throw error
        } finally {
            sourceLease?.let { source -> withContext(NonCancellable) {
                val sourceHeld = lease.copy(owner = sourceOwner, workspaceLease = source)
                val primary = primaryFailure
                if (primary == null) release(sourceHeld)
                else cleanup(primary, session.id, "source_lease_release") { release(sourceHeld) }
            } }
        }
    }

    /** Caller first proves the native writer has ended. A capture failure stays uncertain. */
    suspend fun finish(lease: Lease, success: Boolean, report: String): SessionCodingWorkspace {
        var primaryFailure: Throwable? = null
        try {
            val record = lease.record
            if (success) {
                val before = workspaces.verificationSnapshot(record.attempt.path, operation(lease, "capture-before"))
                    ?: error("Проверка результата недоступна")
                save(lease, record.copy(phase = SessionCodingWorkspacePhase.CAPTURING,
                    attempt = record.attempt.copy(report = report, verificationSnapshot = before)))
                val sha = workspaces.capture(lease.record.attempt, operation(lease, "capture"))
                check(sha.isNotBlank()) { "Git не подтвердил результат" }
                val snapshot = workspaces.verificationSnapshot(record.attempt.path, operation(lease, "capture-after"))
                    ?: error("Проверка результата недоступна")
                check(snapshot == before) { "Рабочая копия изменилась во время сохранения коммита" }
                save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.CAPTURED, resultSnapshot = snapshot,
                    attempt = lease.record.attempt.copy(resultCommit = sha, verificationSnapshot = snapshot)))
            } else save(lease, record.copy(phase = SessionCodingWorkspacePhase.STOPPED, attempt = record.attempt.copy(interrupted = true)))
            return lease.record
        } catch (error: Throwable) {
            primaryFailure = error
            withContext(NonCancellable) { cleanup(error, lease.sessionId, "workspace_checkpoint") {
                save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.UNKNOWN,
                    error = "Не удалось подтвердить сохранение рабочей копии"))
            } }
            throw error
        } finally {
            try { withContext(NonCancellable) { release(lease) } }
            catch (error: Throwable) {
                AppLog.error("coding.workspace", "lease.release.failed", fields = mapOf(
                    "sessionId" to lease.sessionId, "causeType" to error::class.simpleName.orEmpty()))
                withContext(NonCancellable) { cleanup(error, lease.sessionId, "release_checkpoint") {
                    save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.UNKNOWN,
                        error = "Освобождение рабочей копии не подтверждено"))
                } }
                val primary = primaryFailure
                if (primary == null) throw error
                if (primary !== error) primary.addSuppressed(error)
            }
        }
    }

    /** Keep the actual writer lease while process termination is unproven. */
    suspend fun quarantine(lease: Lease) {
        retained.withLock { uncertainLeases[checkNotNull(lease.workspaceLease).token] = lease }
        save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.UNKNOWN, error = "Остановка runtime не подтверждена"))
    }

    suspend fun releaseStopped(sessionId: String) = withContext(NonCancellable) {
        val leases = retained.withLock { uncertainLeases.values.filter { it.sessionId == sessionId } }
        var primary: Throwable? = null
        leases.forEach { lease ->
            try {
                workspaces.reconcile(lease.record.attempt, operation(lease, "release-reconcile"))
                save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.STOPPED, attempt = lease.record.attempt.copy(interrupted = true)))
                release(lease)
            } catch (failure: Throwable) {
                cleanup(failure, sessionId, "reconciliation_checkpoint") {
                    save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.UNKNOWN,
                        error = "Освобождение рабочей копии не подтверждено"))
                }
                AppLog.error("coding.workspace", "lease.reconciliation.failed", fields = mapOf(
                    "sessionId" to sessionId, "causeType" to failure::class.simpleName.orEmpty()))
                val previous = primary
                if (previous == null) primary = failure
                else if (failure is CancellationException && previous !is CancellationException) {
                    if (failure !== previous) failure.addSuppressed(previous)
                    primary = failure
                } else if (failure !== previous) previous.addSuppressed(failure)
            }
        }
        // Source and execution paths can retain separate handles for the same generation.
        // A later successful release must not overwrite the first failure with STOPPED.
        val remaining = retained.withLock { uncertainLeases.values.filter { it.sessionId == sessionId } }
        if (remaining.isNotEmpty()) {
            val failure = primary ?: IllegalStateException("Освобождение рабочей копии не подтверждено").also { primary = it }
            remaining.distinctBy { it.organismId to it.record.generation }.forEach { lease ->
                cleanup(failure, sessionId, "retained_workspace_checkpoint") {
                    val current = store.get(lease.organismId).sessions[sessionId]?.workspace
                    if (current?.generation == lease.record.generation) save(lease,
                        current.copy(phase = SessionCodingWorkspacePhase.UNKNOWN,
                            error = "Освобождение рабочей копии не подтверждено"))
                }
            }
        }
        primary?.let { throw it }
    }

    suspend fun inspect(result: SessionResult): String? {
        val organism = store.organisms.value.values.firstOrNull { result.sessionId in it.sessions } ?: return null
        val record = store.get(organism.id).resultWorkspace(result) ?: return null
        val actual = workspaces.verificationSnapshot(record.attempt.path)
        return if (actual == record.resultSnapshot) actual else null
    }
}
