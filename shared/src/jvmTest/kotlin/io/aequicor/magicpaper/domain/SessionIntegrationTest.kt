package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.GitPlanningWorkspace
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SessionIntegrationTest {
    private class Checks : SessionIntegrationCheckRunner {
        var runs = 0
        var runBody: suspend (String, String, List<String>) -> SessionIntegrationCheck = { _, _, command -> SessionIntegrationCheck(command, 0, "Build passed") }
        var reconcileBody: suspend (String) -> Unit = {}
        override suspend fun run(path: String, id: String, command: List<String>): SessionIntegrationCheck { runs++; return runBody(path, id, command) }
        override fun abort(id: String) = Unit
        override suspend fun reconcile(id: String) = reconcileBody(id)
    }
    private class Fixture {
        val source = Files.createTempDirectory("session-integration-source").toFile()
        val port = GitPlanningWorkspace(Files.createTempDirectory("session-integration-work").toFile())
        val project = CodingProject("project", "Project", source.path, 1)
        val checks = Checks()
        val helper = SessionIntegrationWorkspaces(port, checks) { setOf("test-secret-token") }
        lateinit var saved: SessionIntegration
        var checkpoint: suspend (SessionIntegration) -> Unit = { saved = it }
        init {
            git(source, "init", "-b", "main"); git(source, "config", "user.name", "Fixture"); git(source, "config", "user.email", "fixture@example.com")
            File(source, "initial.txt").writeText("initial\n"); git(source, "add", "."); git(source, "commit", "-m", "Initial")
        }
        suspend fun input(id: String, file: String = "$id.txt", content: String = id): SessionIntegrationInput {
            val owner = project.copy(id = "prepare-$id")
            assertTrue(port.acquire(owner))
            val workspace = try { port.prepare(project, id) } finally { port.release(owner) }
            var attempt = port.stage(project, workspace, StageAttempt(id, "session-$id", StageAssignment("", "")))
            File(attempt.path, file).writeText(content)
            attempt = attempt.copy(report = "Milestone $id", resultCommit = port.capture(attempt.copy(report = "Milestone $id")))
            val snapshot = port.verificationSnapshot(attempt.path)
            return SessionIntegrationInput(SessionResult("result-$id", attempt.sessionId, 1, "root", "Verified", sourceVersion = snapshot,
                commitSha = attempt.resultCommit, accepted = true), SessionCodingWorkspace(1, id, attempt,
                phase = SessionCodingWorkspacePhase.CAPTURED, workspace = workspace, sourceSnapshot = port.verificationSnapshot(source.path),
                resultSnapshot = snapshot, sourcePath = source.path))
        }
        suspend fun request(inputs: List<SessionIntegrationInput>, id: String = "integration") = SessionIntegration(SessionIntegrationRequest(id, "organism", "root", 1,
            inputs.map { it.result.id }, listOf(listOf("./gradlew", "check")), source.path, port.verificationSnapshot(source.path))).also { saved = it }
        suspend fun run(inputs: List<SessionIntegrationInput>, initial: SessionIntegration? = null, sourceLeaseHeld: Boolean = false) =
            helper.integrate(project, initial ?: request(inputs), inputs, sourceLeaseHeld, checkpoint)
    }

    @Test fun combinesAcceptedCommitsChecksTheCombinedFilesAndPreservesUserCheckout() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture()
        File(f.source, "initial.txt").writeText("user staged\n"); git(f.source, "add", "initial.txt")
        File(f.source, "initial.txt").appendText("user unstaged\n"); File(f.source, "draft.txt").writeText("user draft")
        val head = git(f.source, "rev-parse", "HEAD"); val index = git(f.source, "write-tree"); val status = git(f.source, "status", "--porcelain")
        val inputs = listOf(f.input("first"), f.input("second"))
        f.checks.runBody = { path, _, command ->
            assertEquals("first", File(path, "first.txt").readText()); assertEquals("second", File(path, "second.txt").readText())
            assertFalse(f.port.acquire(f.project.copy(id = "competing-check", path = path)))
            SessionIntegrationCheck(command, 0, "Build passed test-secret-token")
        }
        val result = f.run(inputs)
        assertEquals(SessionIntegrationPhase.VERIFIED, result.phase)
        assertEquals(AcceptanceStatus.ACCEPTED, result.acceptance?.status)
        assertEquals(inputs.map { it.result.id }, result.mergedResultIds)
        assertFalse(result.checkResults.single().output.contains("test-secret-token"))
        assertEquals("first", git(f.source, "show", "${result.commitSha}:first.txt"))
        assertEquals("second", git(f.source, "show", "${result.commitSha}:second.txt"))
        assertEquals(head, git(f.source, "rev-parse", "HEAD")); assertEquals(index, git(f.source, "write-tree")); assertEquals(status, git(f.source, "status", "--porcelain"))
        assertFalse(File(f.source, "first.txt").exists())
        assertFailsWith<IllegalArgumentException> { f.run(inputs, result) }
        assertEquals(1, f.checks.runs)
    } }

    @Test fun unresolvedMergeConflictNeverRunsChecksOrReplays() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture(); val inputs = listOf(f.input("first", "initial.txt", "first"), f.input("second", "initial.txt", "second"))
        val result = f.run(inputs)
        assertEquals(SessionIntegrationPhase.CONFLICT, result.phase); assertEquals(0, f.checks.runs)
        assertTrue(result.commitSha.isBlank()); assertEquals("initial\n", File(f.source, "initial.txt").readText())
        assertFailsWith<IllegalArgumentException> { f.run(inputs, result) }
    } }

    @Test fun staleSourceBlocksBeforePreparingIntegration() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture(); val inputs = listOf(f.input("first")); val request = f.request(inputs)
        File(f.source, "initial.txt").writeText("later user edit")
        val result = f.run(inputs, request)
        assertEquals(SessionIntegrationPhase.BLOCKED, result.phase); assertNull(result.workspace); assertEquals(0, f.checks.runs)
    } }

    @Test fun failedBuildCannotPublishVerifiedCommit() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture(); val inputs = listOf(f.input("first"))
        f.checks.runBody = { _, _, command -> SessionIntegrationCheck(command, 1, "Compilation failed") }
        val result = f.run(inputs)
        assertEquals(SessionIntegrationPhase.BLOCKED, result.phase); assertEquals(AcceptanceStatus.FAILED, result.acceptance?.status)
        assertTrue(result.commitSha.isBlank())
    } }

    @Test fun sourceChangesDuringCheckCannotBeAccepted() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture(); val inputs = listOf(f.input("first"))
        f.checks.runBody = { _, _, command -> File(f.source, "initial.txt").writeText("changed"); SessionIntegrationCheck(command, 0, "Pass") }
        assertEquals(SessionIntegrationPhase.UNKNOWN, f.run(inputs).phase)
        assertTrue(f.saved.commitSha.isBlank())
    } }

    @Test fun failureSavingMergedCheckpointPreservesUnknownAndDoesNotReplay() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture(); val inputs = listOf(f.input("first")); var injected = false
        f.checkpoint = { record ->
            if (!injected && record.mergedResultIds.isNotEmpty()) { injected = true; error("save failed after Git merge") }
            f.saved = record
        }
        val result = f.run(inputs)
        assertEquals(SessionIntegrationPhase.UNKNOWN, result.phase); assertEquals(0, f.checks.runs)
        assertEquals("first", File(result.workspace!!.integrationPath, "first.txt").readText())
        assertFailsWith<IllegalArgumentException> { f.run(inputs, result) }
    } }

    @Test fun cancellationKeepsWriterUntilCheckReconciliationCompletes() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture(); val inputs = listOf(f.input("first"))
        val started = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        f.checks.runBody = { _, _, _ -> started.complete(Unit); awaitCancellation() }
        f.checks.reconcileBody = { cleaning.complete(Unit); cleaned.await() }
        val job = launch { f.run(inputs) }
        try {
            withTimeout(10_000) { started.await() }; job.cancel(); withTimeout(5_000) { cleaning.await() }
            assertFalse(job.isCompleted)
            assertFalse(f.port.acquire(f.project.copy(id = "early", path = f.saved.workspace!!.integrationPath)))
            cleaned.complete(Unit); withTimeout(5_000) { job.join() }
            assertEquals(SessionIntegrationPhase.UNKNOWN, f.saved.phase)
            val owner = f.project.copy(id = "after", path = f.saved.workspace!!.integrationPath)
            assertTrue(f.port.acquire(owner)); f.port.release(owner)
        } finally { cleaned.complete(Unit); job.cancelAndJoin() }
    } }

    @Test fun existingParentWriterLeaseCanBeBorrowedWithoutReleasingIt() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture(); val inputs = listOf(f.input("first")); val parent = f.project.copy(id = "root-runtime")
        assertTrue(f.port.acquire(parent))
        try {
            assertEquals(SessionIntegrationPhase.VERIFIED, f.run(inputs, sourceLeaseHeld = true).phase)
            assertFalse(f.port.acquire(f.project.copy(id = "other-root")))
        } finally { f.port.release(parent) }
    } }

    @Test fun legacyResultWithoutCapturedSourceIdentityIsRejected() = runTest { withContext(Dispatchers.Default) {
        val f = Fixture(); val input = f.input("first"); val inputs = listOf(input.copy(workspace = input.workspace.copy(sourcePath = "")))
        assertFailsWith<IllegalArgumentException> { f.run(inputs) }; assertEquals(0, f.checks.runs)
    } }

    companion object {
        private fun git(path: File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", path.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output.trim()
        }
    }
}
