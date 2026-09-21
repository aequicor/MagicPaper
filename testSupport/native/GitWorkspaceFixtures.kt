package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.checks.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow

/** Filesystem protocol fixture. Native group authority is exercised separately by checks' opt-in OS tests. */
fun testGitChecks(): CommandChecks = object : CommandChecks {
    private val results = ConcurrentHashMap<CheckRef, CheckResult>()
    private val bytes = ConcurrentHashMap<CheckRef, ByteArray>()
    override val progress = emptyFlow<CheckProgress>()
    override suspend fun run(command: CheckCommand): CheckResult = withContext(Dispatchers.IO) {
        require(command.arguments.first() == "git")
        val stderr = File.createTempFile("test-git-stderr", ".txt")
        val process = ProcessBuilder(command.arguments).directory(File(command.workspace)).redirectError(stderr).apply {
            environment().keys.removeIf { it.startsWith("GIT_", ignoreCase = true) }
            environment().putAll(command.environment)
        }.start()
        try {
            process.outputStream.close()
            val output = process.inputStream.use { it.readBytes() }
            val code = process.waitFor()
            bytes[command.ref] = output
            CheckResult(stderr.readText(), code, binaryOutput = CheckOutputRef(UUID.randomUUID().toString(), output.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(output).joinToString("") { "%02x".format(it) })).also { results[command.ref] = it }
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
            check(stderr.delete() || !stderr.exists())
        }
    }
    override suspend fun inspect(ref: CheckRef) = results[ref]
    override suspend fun readOutput(ref: CheckRef) = checkNotNull(bytes[ref]).copyOf()
    override suspend fun unresolved(resource: String) = emptySet<CheckRef>()
    override fun abort(sessionId: String) = Unit
    override fun abortAll() = Unit
    override suspend fun reconcile(sessionId: String) = Unit
    override suspend fun prepareForReset() = Unit
    override suspend fun resumeAfterReset() = Unit
    override suspend fun close() = Unit
}

/** Test-only convenience; production always receives the application's shared authority explicitly. */
fun testGitPlanningWorkspace(dataRoot: File, checks: CommandChecks = testGitChecks(), checkpoint: (String) -> Unit = {}): GitPlanningWorkspace =
    GitPlanningWorkspace(dataRoot, GitWorkspaceAuthority(checks, dataRoot, checkpoint), checkpoint)

/** Each filesystem test action owns and releases a real lease, even across simulated crash/reopen boundaries. */
private suspend fun <T> GitPlanningWorkspace.testOperation(path: String, action: suspend (WorkspaceOperation) -> T): T {
    val request = UUID.randomUUID().toString()
    val lease = checkNotNull(acquire(CodingProject("test-$request", "Test", path, 0), request))
    var primary: Throwable? = null
    try { return action(WorkspaceOperation(lease, "test-operation")) }
    catch (failure: Throwable) { primary = failure; throw failure }
    finally { withContext(NonCancellable) {
        try { release(lease) } catch (cleanup: Throwable) {
            val failure = primary
            if (failure == null) throw cleanup
            if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
    } }
}
suspend fun GitPlanningWorkspace.prepare(project: CodingProject, runId: String) = testOperation(project.path) { prepare(project, runId, it) }
suspend fun GitPlanningWorkspace.stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt) = testOperation(project.path) { stage(project, workspace, attempt, it) }
suspend fun GitPlanningWorkspace.capture(attempt: StageAttempt) = testOperation(attempt.path) { capture(attempt, it) }
suspend fun GitPlanningWorkspace.integrate(workspace: PlanWorkspace, attempt: StageAttempt) = testOperation(workspace.integrationPath) { integrate(workspace, attempt, it) }
suspend fun GitPlanningWorkspace.finishConflict(workspace: PlanWorkspace, attempt: StageAttempt) = testOperation(workspace.integrationPath) { finishConflict(workspace, attempt, it) }
suspend fun GitPlanningWorkspace.apply(project: CodingProject, workspace: PlanWorkspace) = testOperation(project.path) { apply(project, workspace, it) }
suspend fun GitPlanningWorkspace.reconcile(attempt: StageAttempt) = testOperation(attempt.path) { reconcile(attempt, it) }
suspend fun GitPlanningWorkspace.validateIntegration(workspace: PlanWorkspace) = testOperation(workspace.integrationPath) { validateIntegration(workspace, it) }
suspend fun GitPlanningWorkspace.finishDeliveryConflict(path: String) = testOperation(path) { finishDeliveryConflict(path, it) }
