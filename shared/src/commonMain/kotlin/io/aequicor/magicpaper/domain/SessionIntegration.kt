package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class SessionIntegrationPhase { INTENT, PREPARING, MERGING, CONFLICT, VERIFYING, VERIFIED, BLOCKED, UNKNOWN }

/** Immutable application-authorized input; command arguments are passed to the existing sandbox. */
@Serializable data class SessionIntegrationRequest(
    val id: String, val organismId: String, val actorSessionId: String, val generation: Long,
    val resultIds: List<String>, val checks: List<List<String>>, val sourcePath: String, val sourceSnapshot: String,
)

@Serializable data class SessionIntegrationCheck(
    val command: List<String>, val exitCode: Int?, val output: String, val blockedReason: String? = null,
)

@Serializable data class SessionIntegration(
    val request: SessionIntegrationRequest,
    val phase: SessionIntegrationPhase = SessionIntegrationPhase.INTENT,
    val workspace: PlanWorkspace? = null,
    val mergedResultIds: List<String> = emptyList(),
    val checkResults: List<SessionIntegrationCheck> = emptyList(),
    val acceptance: AcceptanceRecord? = null,
    val commitSha: String = "", val snapshot: String = "", val error: String = "",
)

data class SessionIntegrationInput(val result: SessionResult, val workspace: SessionCodingWorkspace)

interface SessionIntegrationCheckRunner {
    suspend fun run(path: String, id: String, command: List<String>): SessionIntegrationCheck
    fun abort(id: String)
    suspend fun reconcile(id: String)
}

/** Git, native checks and aggregate checkpoints are separate durability domains. */
class SessionIntegrationWorkspaces(
    private val workspaces: PlanningWorkspace,
    private val checks: SessionIntegrationCheckRunner,
    private val knownSecrets: () -> Set<String> = { emptySet() },
) {
    private val preparation = Mutex()
    private val leaseLock = Mutex()
    private val retained = mutableMapOf<String, List<CodingProject>>()
    private fun checkId(request: SessionIntegrationRequest, index: Int) = "session-integration-${request.id}-check-$index"
    private fun safe(text: String) = PlanningDiagnostics.redact(text, knownSecrets())

    /** Caller has already persisted INTENT and proved the authority of the exact generation. */
    suspend fun integrate(project: CodingProject, initial: SessionIntegration, inputs: List<SessionIntegrationInput>,
        sourceLeaseHeld: Boolean = false, checkpoint: suspend (SessionIntegration) -> Unit): SessionIntegration {
        val request = initial.request
        require(initial.phase == SessionIntegrationPhase.INTENT) { "Исход интеграции уже сохранён; повтор не запускает работу" }
        require(request.sourcePath == project.path && request.sourceSnapshot.isNotBlank()) { "Исходная рабочая копия не подтверждена" }
        require(request.resultIds.isNotEmpty() && request.resultIds.size <= 32 && request.resultIds.distinct() == request.resultIds) { "Некорректный набор результатов" }
        require(inputs.map { it.result.id } == request.resultIds) { "Состав результатов изменился" }
        require(request.checks.isNotEmpty() && request.checks.size <= 8 && request.checks.all { command ->
            command.isNotEmpty() && command.size <= 128 && command.all { it.length <= 16_384 && '\u0000' !in it } && command.first().isNotBlank()
        }) { "Нужна итоговая проверка и корректные аргументы" }
        inputs.forEach { (result, workspace) ->
            require(result.accepted && result.recipient == request.actorSessionId && result.generation == workspace.generation &&
                workspace.sourcePath == request.sourcePath && workspace.phase == SessionCodingWorkspacePhase.CAPTURED &&
                result.commitSha.isNotBlank() && result.commitSha == workspace.attempt.resultCommit &&
                result.sourceVersion == workspace.resultSnapshot) { "Результат не принадлежит подтверждённой исходной рабочей копии" }
        }
        var record = initial
        suspend fun save(next: SessionIntegration) { checkpoint(next); record = next }
        val owned = mutableListOf<CodingProject>()
        var externalStarted = false
        var cleanupConfirmed = true
        try {
            if (!sourceLeaseHeld) {
                val owner = project.copy(id = "session-integration-${request.id}-source")
                withContext(NonCancellable) {
                    check(workspaces.acquire(owner)) { "Исходная рабочая копия занята" }
                    owned += owner
                }
            }
            currentCoroutineContext().ensureActive()
            require(workspaces.verificationSnapshot(project.path) == request.sourceSnapshot) { "Исходники изменились до интеграции" }
            inputs.forEach { input -> require(workspaces.verificationSnapshot(input.workspace.attempt.path) == input.result.sourceVersion) {
                "Рабочая копия результата изменилась"
            } }
            save(record.copy(phase = SessionIntegrationPhase.PREPARING))
            externalStarted = true
            val workspace = preparation.withLock { workspaces.prepare(project, "session-integration-${request.id}") }
            require(workspace.git && workspace.integrationPath != project.path && workspace.baseCommit.isNotBlank()) { "Изолированная Git-интеграция недоступна" }
            require(workspaces.verificationSnapshot(project.path) == request.sourceSnapshot) { "Исходники изменились при подготовке интеграции" }
            val owner = project.copy(id = "session-integration-${request.id}", path = workspace.integrationPath)
            withContext(NonCancellable) {
                check(workspaces.acquire(owner)) { "Рабочая копия интеграции занята" }
                owned += owner
            }
            save(record.copy(workspace = workspace, phase = SessionIntegrationPhase.MERGING))
            for (input in inputs) {
                currentCoroutineContext().ensureActive()
                require(workspaces.verificationSnapshot(input.workspace.attempt.path) == input.result.sourceVersion) { "Рабочая копия результата изменилась" }
                if (!workspaces.integrate(workspace, input.workspace.attempt)) {
                    save(record.copy(phase = SessionIntegrationPhase.CONFLICT, error = "Конфликт интеграции; рабочая копия сохранена"))
                    break
                }
                save(record.copy(mergedResultIds = record.mergedResultIds + input.result.id))
            }
            if (record.phase != SessionIntegrationPhase.CONFLICT) {
                workspaces.validateIntegration(workspace)
                val snapshot = workspaces.verificationSnapshot(workspace.integrationPath) ?: error("Снимок интеграции недоступен")
                save(record.copy(phase = SessionIntegrationPhase.VERIFYING, snapshot = snapshot))
                request.checks.forEachIndexed { index, command ->
                    currentCoroutineContext().ensureActive()
                    val result = checks.run(workspace.integrationPath, checkId(request, index), command)
                    require(result.command == command) { "Проверка выполнила другую команду" }
                    checks.reconcile(checkId(request, index))
                    save(record.copy(checkResults = record.checkResults + result.copy(command = result.command.map(::safe),
                        output = safe(result.output), blockedReason = result.blockedReason?.let(::safe))))
                }
                workspaces.validateIntegration(workspace)
                val actual = workspaces.verificationSnapshot(workspace.integrationPath)
                val criteria = request.checks.indices.map { AcceptanceCriterion("check-$it", "Итоговая проверка ${it + 1}", environment = EvidenceEnvironment.LOCAL_TEST, checkId = "session-integration") }
                val evidence = record.checkResults.mapIndexed { index, check ->
                    AcceptanceEvidence(criteria[index].id, EvidenceEnvironment.LOCAL_TEST, snapshot,
                        when { check.blockedReason != null || check.exitCode == null -> CheckStatus.BLOCKED; check.exitCode == 0 -> CheckStatus.PASS; else -> CheckStatus.FAIL },
                        check.blockedReason ?: check.output, listOf("session-integration:${request.id}/checks/$index"))
                }
                val findings = evidence.mapIndexed { index, proof -> AcceptanceFinding(proof.criterionId, proof.status, criteria[index].description, proof.detail, proof.artifacts) }
                val acceptance = AcceptanceGate.evaluate(AcceptanceRecord(request.id, request.id, snapshot, criteria, findings, evidence), criteria, actual)
                save(record.copy(acceptance = acceptance))
                require(workspaces.verificationSnapshot(project.path) == request.sourceSnapshot) { "Исходники изменились во время итоговой проверки" }
                if (!acceptance.permitsProgress) save(record.copy(phase = SessionIntegrationPhase.BLOCKED, error = safe(acceptance.summary())))
                else {
                    val sha = workspaces.capture(StageAttempt("session-integration-${request.id}-final", request.actorSessionId,
                        StageAssignment("", ""), path = workspace.integrationPath, baseCommit = workspace.baseCommit,
                        report = "Объединённый результат ${request.id}", verificationSnapshot = snapshot))
                    require(sha.isNotBlank() && workspaces.verificationSnapshot(workspace.integrationPath) == snapshot) { "Результат изменился при фиксации интеграции" }
                    // Keep VERIFYING until all native ownership and writer leases are confirmed closed.
                    save(record.copy(commitSha = sha))
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                save(record.copy(phase = if (externalStarted) SessionIntegrationPhase.UNKNOWN else SessionIntegrationPhase.BLOCKED,
                    error = safe(error.message.orEmpty())))
            }
            if (error is CancellationException) throw error
        } finally {
            withContext(NonCancellable) {
                request.checks.indices.forEach { index ->
                    runCatching { checks.abort(checkId(request, index)); checks.reconcile(checkId(request, index)) }.onFailure { cleanupConfirmed = false }
                }
                val remaining = mutableListOf<CodingProject>()
                owned.asReversed().forEach { owner ->
                    if (!cleanupConfirmed) remaining += owner
                    else runCatching { workspaces.release(owner) }.onFailure { cleanupConfirmed = false; remaining += owner }
                }
                if (remaining.isNotEmpty()) leaseLock.withLock { retained[request.id] = remaining }
                if (!cleanupConfirmed) save(record.copy(phase = SessionIntegrationPhase.UNKNOWN, error = "Остановка проверки или освобождение рабочей копии не подтверждены"))
            }
        }
        if (record.phase == SessionIntegrationPhase.VERIFYING && record.commitSha.isNotBlank() && cleanupConfirmed)
            save(record.copy(phase = SessionIntegrationPhase.VERIFIED))
        return record
    }

    /** Reconcile cleanup only. This never repeats a merge, check or Git capture. */
    suspend fun reconcile(record: SessionIntegration) = withContext(NonCancellable) {
        record.request.checks.indices.forEach { index -> checks.abort(checkId(record.request, index)); checks.reconcile(checkId(record.request, index)) }
        val owners = leaseLock.withLock { retained[record.request.id].orEmpty() }
        owners.forEach { owner ->
            workspaces.release(owner)
            leaseLock.withLock { retained[record.request.id] = retained[record.request.id].orEmpty() - owner }
        }
        leaseLock.withLock { if (retained[record.request.id].isNullOrEmpty()) retained.remove(record.request.id) }
    }
}
