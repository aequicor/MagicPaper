package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class SessionCodingWorkspacePhase { PREPARING, READY, RUNNING, CAPTURING, CAPTURED, STOPPED, UNKNOWN }

/** An external-effect checkpoint in the session aggregate; never a claim of Git/DB atomicity. */
@Serializable data class SessionCodingWorkspace(
    val generation: Long,
    val runId: String,
    val attempt: StageAttempt,
    val phase: SessionCodingWorkspacePhase = SessionCodingWorkspacePhase.PREPARING,
    val workspace: PlanWorkspace? = null,
    val sourceSnapshot: String? = null,
    val resultSnapshot: String? = null,
    val error: String = "",
    val sourcePath: String = "",
)

/** Reuses the planner's worktrees, private-index commits and writer leases. Never applies to the checkout. */
internal class SessionCodingWorkspaces(
    private val workspaces: PlanningWorkspace,
    private val store: SessionOrganismStore,
) {
    internal data class Lease(val organismId: String, val sessionId: String, val owner: CodingProject,
        var record: SessionCodingWorkspace)
    private val preparation = Mutex()
    private val retained = Mutex()
    private val uncertainLeases = mutableMapOf<String, Lease>()
    suspend fun retainedSessionIds(): Set<String> = retained.withLock { uncertainLeases.values.map { it.sessionId }.toSet() }

    private suspend fun save(lease: Lease, record: SessionCodingWorkspace) {
        store.recordWorkspace(lease.organismId, lease.sessionId, record.generation, record)
        lease.record = record
    }

    suspend fun open(project: CodingProject, session: CodingSession, task: SessionTask, parentOwnsSource: Boolean = false): Lease = preparation.withLock {
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
        var sourceOwned = false
        var executionOwned = false
        var externalStarted = false
        var lease = Lease(organismId, session.id, project.copy(id = "$key-source"), initial)
        try {
            // An active managed parent already owns this exact path. Preparation only reads
            // its files through a private index; the child writer gets a different lease.
            if (!parentOwnsSource) withContext(NonCancellable) { sourceOwned = workspaces.acquire(lease.owner) }
            check(parentOwnsSource || sourceOwned) { "Исходная рабочая копия занята" }
            currentCoroutineContext().ensureActive()
            val before = workspaces.verificationSnapshot(project.path)
                ?: error("Проверка исходников недоступна")
            require(task.sourceVersion.isBlank() || task.sourceVersion == before) { "Исходники задания изменились; создайте новое задание" }
            externalStarted = true
            val workspace = workspaces.prepare(project, initial.runId)
            require(workspace.git) { "Дочерняя разработка требует Git и отдельной рабочей копии" }
            require(workspaces.verificationSnapshot(project.path) == before) { "Исходники изменились при создании рабочей копии" }
            val attempt = workspaces.stage(project, workspace, initial.attempt)
            require(attempt.path.isNotBlank() && attempt.path != project.path && attempt.baseCommit.isNotBlank()) { "Изолированная рабочая копия не подтверждена" }
            val ready = initial.copy(workspace = workspace, attempt = attempt, sourceSnapshot = before, phase = SessionCodingWorkspacePhase.READY)
            save(lease, ready)
            val executionOwner = project.copy(id = key, path = attempt.path)
            withContext(NonCancellable) {
                executionOwned = workspaces.acquire(executionOwner)
                if (executionOwned) lease = lease.copy(owner = executionOwner)
            }
            check(executionOwned) { "Рабочая копия дочерней сессии занята" }
            workspaces.reconcile(attempt)
            currentCoroutineContext().ensureActive()
            save(lease, ready.copy(phase = SessionCodingWorkspacePhase.RUNNING))
            if (sourceOwned) withContext(NonCancellable) {
                workspaces.release(project.copy(id = "$key-source"))
                sourceOwned = false
            }
            lease
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                if (executionOwned) runCatching { workspaces.release(lease.owner) }
                val phase = if (externalStarted) SessionCodingWorkspacePhase.UNKNOWN else SessionCodingWorkspacePhase.STOPPED
                runCatching { save(lease, lease.record.copy(phase = phase, error = error.message.orEmpty())) }
                if (externalStarted) runCatching { store.observe(organismId, session.id, session.runtimeGeneration, SessionObservedState.UNKNOWN) }
            }
            throw error
        } finally {
            if (sourceOwned) withContext(NonCancellable) { runCatching { workspaces.release(project.copy(id = "$key-source")) } }
        }
    }

    /** Caller first proves the native writer has ended. A capture failure stays uncertain. */
    suspend fun finish(lease: Lease, success: Boolean, report: String): SessionCodingWorkspace {
        try {
            val record = lease.record
            if (success) {
                val before = workspaces.verificationSnapshot(record.attempt.path)
                    ?: error("Проверка результата недоступна")
                save(lease, record.copy(phase = SessionCodingWorkspacePhase.CAPTURING,
                    attempt = record.attempt.copy(report = report, verificationSnapshot = before)))
                val sha = workspaces.capture(lease.record.attempt)
                check(sha.isNotBlank()) { "Git не подтвердил результат" }
                val snapshot = workspaces.verificationSnapshot(record.attempt.path)
                    ?: error("Проверка результата недоступна")
                check(snapshot == before) { "Рабочая копия изменилась во время сохранения коммита" }
                save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.CAPTURED, resultSnapshot = snapshot,
                    attempt = lease.record.attempt.copy(resultCommit = sha, verificationSnapshot = snapshot)))
            } else save(lease, record.copy(phase = SessionCodingWorkspacePhase.STOPPED, attempt = record.attempt.copy(interrupted = true)))
            return lease.record
        } catch (error: Throwable) {
            runCatching { save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.UNKNOWN, error = error.message.orEmpty())) }
            throw error
        } finally {
            try { workspaces.release(lease.owner) }
            catch (error: Exception) {
                retained.withLock { uncertainLeases[lease.owner.id] = lease }
                runCatching { save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.UNKNOWN, error = error.message.orEmpty())) }
                throw error
            }
        }
    }

    /** Keep the actual writer lease while process termination is unproven. */
    suspend fun quarantine(lease: Lease) {
        retained.withLock { uncertainLeases[lease.owner.id] = lease }
        save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.UNKNOWN, error = "Остановка runtime не подтверждена"))
    }

    suspend fun releaseStopped(sessionId: String) {
        val leases = retained.withLock { uncertainLeases.values.filter { it.sessionId == sessionId } }
        leases.forEach { lease ->
            workspaces.reconcile(lease.record.attempt)
            save(lease, lease.record.copy(phase = SessionCodingWorkspacePhase.STOPPED, attempt = lease.record.attempt.copy(interrupted = true)))
            workspaces.release(lease.owner)
            retained.withLock { if (uncertainLeases[lease.owner.id] === lease) uncertainLeases.remove(lease.owner.id) }
        }
    }

    suspend fun inspect(result: SessionResult): String? {
        val organism = store.organisms.value.values.firstOrNull { result.sessionId in it.sessions } ?: return null
        val record = store.get(organism.id).resultWorkspace(result) ?: return null
        val actual = workspaces.verificationSnapshot(record.attempt.path)
        return if (actual == record.resultSnapshot) actual else null
    }
}
