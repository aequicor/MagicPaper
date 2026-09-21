package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GitPlanningWorkspaceTest {
    @Test fun executionRejectsSharedGitCheckoutAndAliasesButAcceptsManagedWorktree() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val head = git(source, "rev-parse", "HEAD")
        val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-path-guard-").toFile(), checks = testGitChecks())
        assertFailsWith<IllegalArgumentException> { port.validateExecutionPath(project, source.path) }
        assertFailsWith<IllegalArgumentException> { port.validateExecutionPath(project, File(source, ".").path) }
        val workspace = port.prepare(project, "run")
        port.validateExecutionPath(project, workspace.integrationPath)
        val worker = port.stage(project, workspace, attempt("isolated"))
        port.validateExecutionPath(project, worker.path)
        assertEquals(head, git(source, "rev-parse", "HEAD"))
    }

    @Test fun failedLeaseReleaseRetainsItsIdentityAndCanBeRetriedAtEveryBoundary() = runTest {
        for (boundary in listOf("project-lock-releasing", "project-lock-released")) {
            val source = Files.createTempDirectory("planning-release-source-").toFile()
            val data = Files.createTempDirectory("planning-release-data-").toFile()
            val project = CodingProject("owner", "Source", source.path, 1)
            val alias = project.copy(id = "alias", path = File(source, ".").path)
            var interrupted = false
            val failure = IOException("injected $boundary failure")
            val port = testGitPlanningWorkspace(data, checks = testGitChecks()) { event ->
                if (event == boundary && !interrupted) { interrupted = true; throw failure }
            }
            val lease = assertNotNull(port.acquire(project, "request"))
            assertTrue(assertFailsWith<IOException> { port.release(lease) }.containsCause(failure))
            assertTrue(interrupted)
            // Даже погашенный OS-замок не доказывает завершение всей очистки ресурса.
            assertEquals(project.id, port.holderOf(source.path))
            assertNull(port.acquire(project, "request"), "A failed release keeps the original identity: $boundary")
            assertNull(port.acquire(alias, "other-request"), "An alias cannot bypass incomplete release: $boundary")
            port.release(lease)
            port.release(lease)
            val next = testGitPlanningWorkspace(data, checks = testGitChecks())
            val nextLease = assertNotNull(next.acquire(alias, "next-request"), "A confirmed release frees the checkout for another process: $boundary")
            next.release(nextLease)
            assertFalse(File(source, ".git").exists(), "Ownership does not initialize the source repository")
        }
    }

    @Test fun sourceAndExecutionLeasesRequireTheirOwnPathAndReleaseIndependently() = runTest {
        val source = Files.createTempDirectory("planning-lease-source-").toFile()
        val execution = Files.createTempDirectory("planning-lease-execution-").toFile()
        val data = Files.createTempDirectory("planning-lease-store-").toFile()
        val sourceOwner = CodingProject("source", "Source", source.path, 1)
        val executionOwner = sourceOwner.copy(id = "execution", path = execution.path)
        val port = testGitPlanningWorkspace(data, checks = testGitChecks())
        val another = testGitPlanningWorkspace(data, checks = testGitChecks())
        val sourceLease = assertNotNull(port.acquire(sourceOwner, "source-request"))
        val executionLease = assertNotNull(port.acquire(executionOwner, "execution-request"))
        assertFailsWith<IllegalArgumentException> { port.release(sourceLease.copy(canonicalPath = execution.canonicalPath)) }
        assertNull(port.acquire(sourceOwner.copy(id = "source-alias"), "alias-request"))
        port.release(sourceLease)
        // Незанятый путь доступен и параллельному экземпляру: чужое удержание другой папки
        // больше не блокирует весь профиль, пока активна хоть одна блокировка процесса.
        val anotherLease = assertNotNull(another.acquire(sourceOwner, "parallel-request"), "A released path is free for a parallel instance")
        another.release(anotherLease)
        assertNull(another.acquire(executionOwner, "parallel-execution"), "The same canonical path stays exclusive across processes")
        assertNull(port.acquire(executionOwner.copy(id = "execution-alias"), "alias-execution"), "Releasing source retains the execution writer")
        port.release(executionLease)
        another.release(assertNotNull(another.acquire(sourceOwner, "new-request")))
    }

    @Test fun lateReleaseCannotUnlockANewAcquisitionOfTheSameRequest() = runTest {
        val source = Files.createTempDirectory("planning-lease-generation-").toFile()
        val data = Files.createTempDirectory("planning-lease-generation-store-").toFile()
        val project = CodingProject("owner", "Source", source.path, 1)
        val port = testGitPlanningWorkspace(data, checks = testGitChecks())
        val other = testGitPlanningWorkspace(data, checks = testGitChecks())
        val old = assertNotNull(port.acquire(project, "same-request"))
        assertNull(port.acquire(project, "same-request"))
        port.release(old)
        val current = assertNotNull(port.acquire(project, "same-request"))
        assertNotEquals(old.token, current.token)
        port.release(old)
        assertEquals(project.id, port.holderOf(File(source, ".").path))
        assertNull(other.acquire(project.copy(id = "other"), "other-request"))
        for (changed in listOf(
            current.copy(ownerId = "other"),
            current.copy(requestId = "other-request"),
            current.copy(canonicalPath = File(source, "other").path),
        )) assertFailsWith<IllegalArgumentException> { port.release(changed) }
        assertNull(other.acquire(project, "still-held"))
        port.release(current)
        other.release(assertNotNull(other.acquire(project, "released")))
    }

    @Test fun cancelledCleanupRetainsTheExactLeaseUntilRetry() = runTest {
        for (boundary in listOf("project-lock-releasing", "project-lock-released")) {
            val source = Files.createTempDirectory("planning-cancelled-release-").toFile()
            val project = CodingProject("owner", "Source", source.path, 1)
            var fail = true
            val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-cancelled-release-store-").toFile(), checks = testGitChecks()) { event ->
                if (event == boundary && fail) {
                    fail = false
                    throw kotlinx.coroutines.CancellationException("cancelled lease cleanup")
                }
            }
            val lease = assertNotNull(port.acquire(project, "request"))
            assertFailsWith<kotlinx.coroutines.CancellationException> { port.release(lease) }
            assertEquals(project.id, port.holderOf(source.path))
            assertNull(port.acquire(project, "next-request"))
            port.release(lease)
            port.release(assertNotNull(port.acquire(project, "next-request")))
        }
    }

    @Test fun cancellationBeforeLeaseDeliveryReleasesItsUndeliveredOsLock() = runTest {
        val source = Files.createTempDirectory("planning-cancelled-acquire-").toFile()
        val data = Files.createTempDirectory("planning-cancelled-acquire-store-").toFile()
        val project = CodingProject("owner", "Source", source.path, 1)
        lateinit var acquiring: Job
        var cancelAtReturn = true
        var delivered = false
        val port = testGitPlanningWorkspace(data, checks = testGitChecks()) { boundary ->
            if (boundary == "project-lock-acquired" && cancelAtReturn) {
                cancelAtReturn = false
                acquiring.cancel(CancellationException("cancel before lease delivery"))
            }
        }
        acquiring = launch(start = CoroutineStart.LAZY) {
            port.acquire(project, "cancelled-request")
            delivered = true
        }
        acquiring.start()
        acquiring.join()
        assertTrue(acquiring.isCancelled)
        assertFalse(delivered, "The caller never received a usable lease")
        assertNull(port.holderOf(source.path))
        val other = testGitPlanningWorkspace(data, checks = testGitChecks())
        other.release(assertNotNull(other.acquire(project, "new-request")))
    }

    @Test fun undeliveredLeaseCleanupFailurePreservesCancellationAndBlocksNewGrantUntilCleanupSucceeds() = runTest {
        for (cleanupBoundary in listOf("project-lock-releasing", "project-lock-released")) {
            val source = Files.createTempDirectory("planning-undelivered-acquire-").toFile()
            val data = Files.createTempDirectory("planning-undelivered-acquire-store-").toFile()
            val project = CodingProject("owner", "Source", source.path, 1)
            lateinit var acquiring: Job
            var cancelAtReturn = true
            var failCleanup = true
            var delivered = false
            var observedCancellation: CancellationException? = null
            val cleanupFailure = IOException("injected cleanup failure")
            val port = testGitPlanningWorkspace(data, checks = testGitChecks()) { boundary ->
                if (boundary == "project-lock-acquired" && cancelAtReturn) {
                    cancelAtReturn = false
                    acquiring.cancel(CancellationException("cancel before lease delivery"))
                }
                if (boundary == cleanupBoundary && failCleanup) throw cleanupFailure
            }
            acquiring = launch(start = CoroutineStart.LAZY) {
                try {
                    port.acquire(project, "cancelled-request")
                    delivered = true
                } catch (cancelled: CancellationException) {
                    observedCancellation = cancelled
                    throw cancelled
                }
            }
            acquiring.start()
            acquiring.join()
            assertTrue(acquiring.isCancelled)
            assertFalse(delivered)
            val cancelled = assertNotNull(observedCancellation)
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { cause ->
                cause.suppressed.any { it.containsCause(cleanupFailure) }
            }, "Failed cleanup must not replace cancellation")
            assertEquals(project.id, port.holderOf(source.path))
            val alias = project.copy(id = "next-owner", path = File(source, ".").path)
            assertTrue(assertFailsWith<IOException> { port.acquire(alias, "next-request") }.containsCause(cleanupFailure))
            assertEquals(project.id, port.holderOf(source.path), "A failed retry does not grant the path")
            failCleanup = false
            val recovered = assertNotNull(port.acquire(alias, "next-request"))
            assertEquals(alias.id, port.holderOf(source.path))
            assertEquals("next-request", recovered.requestId)
            port.release(recovered)
        }
    }

    // Coroutine stack recovery may copy the outer exception; the original must remain its cause.
    private fun Throwable.containsCause(expected: Throwable): Boolean =
        generateSequence(this) { it.cause }.any { it === expected }

    @Test fun captureReusesAnExistingAgentCommitWithoutFlatteningItsHistory() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-existing-commit-").toFile(), checks = testGitChecks())
        val workspace = port.prepare(project, "run")
        val attempt = port.stage(project, workspace, attempt("committed"))
        File(attempt.path, "result.txt").writeText("checked result")
        git(File(attempt.path), "add", "result.txt")
        git(File(attempt.path), "commit", "-m", "Add verified result")
        val existing = git(File(attempt.path), "rev-parse", "HEAD")
        assertEquals(existing, port.capture(attempt))
        assertTrue(port.integrate(workspace, attempt.copy(resultCommit = existing)))
        assertTrue(git(File(workspace.integrationPath), "log", "--format=%s").contains("Add verified result"))
    }

    @Test fun unchangedMilestoneDoesNotInventCommitAndNewWorktreesUseFeatureBranches() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val originalHead = git(source, "rev-parse", "HEAD")
        val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-empty-").toFile(), checks = testGitChecks())
        val workspace = port.prepare(project, "run")
        val a = port.stage(project, workspace, attempt("empty"))
        assertEquals(originalHead, workspace.baseCommit)
        assertEquals(a.baseCommit, port.capture(a))
        assertTrue(git(File(workspace.integrationPath), "symbolic-ref", "--short", "HEAD").startsWith("magicpaper/"))
        assertTrue(git(File(a.path), "symbolic-ref", "--short", "HEAD").startsWith("magicpaper/"))
        assertEquals(originalHead, git(source, "rev-parse", "HEAD"))
    }

    @Test fun capturePreservesIndexAndRejectsChangedReplay() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-private-index-").toFile(), checks = testGitChecks())
        val workspace = port.prepare(project, "run")
        val a = port.stage(project, workspace, attempt("result")).copy(report = "Add a useful result\nValidation passed")
        File(a.path, "result.txt").writeText("staged")
        git(File(a.path), "add", "result.txt")
        File(a.path, "result.txt").writeText("final content")
        val before = git(File(a.path), "diff", "--cached")
        val captured = port.capture(a)
        assertEquals("Add a useful result", git(source, "show", "-s", "--format=%s", captured))
        assertEquals(before, git(File(a.path), "diff", "--cached"))
        assertEquals("final content", git(source, "show", "$captured:result.txt"))
        File(a.path, "result.txt").writeText("later user edit")
        assertFailsWith<IllegalArgumentException> { port.capture(a) }
        assertEquals("later user edit", File(a.path, "result.txt").readText())
    }

    @Test fun captureRefusesUserWorkingCopyAndDoesNotInitializeNonGitFolder() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-protected-").toFile(), checks = testGitChecks())
        val head = git(source, "rev-parse", "HEAD")
        assertFailsWith<IllegalArgumentException> { port.capture(attempt("unsafe").copy(path = source.path, baseCommit = head)) }
        val plain = Files.createTempDirectory("planning-no-git-").toFile()
        val workspace = port.prepare(project.copy(path = plain.path), "plain")
        assertFalse(workspace.git)
        assertFalse(File(plain, ".git").exists())
    }

    private fun repository(): File {
        val dir = Files.createTempDirectory("magicpaper-planning-test-").toFile()
        git(dir, "init")
        File(dir, "initial.txt").writeText("initial\n")
        git(dir, "add", "."); git(dir, "commit", "-m", "initial")
        return dir
    }
    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@localhost") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }; return out.trim()
    }
    private fun attempt(id: String) = StageAttempt(id, "session-$id", StageAssignment("profile", "model"))
    @Test fun snapshotPreservesDirtyTreeAndIndexAndTransferCanReplay() = runTest {
        val source = repository()
        File(source, "initial.txt").writeText("user staged\n"); git(source, "add", "initial.txt")
        File(source, "initial.txt").appendText("user unstaged\n")
        File(source, "untracked.txt").writeText("user new\n")
        val before = git(source, "diff", "--cached")
        val head = git(source, "rev-parse", "HEAD")
        val project = CodingProject("p", "P", source.path, 1)
        val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-data-").toFile(), checks = testGitChecks())
        val lease = assertNotNull(port.acquire(project, "run"))
        try {
            val workspace = port.prepare(project, "run", WorkspaceOperation(lease, "prepare"))
            val a = port.stage(project, workspace, attempt("a"), WorkspaceOperation(lease, "stage-a"))
            assertEquals(File(source, "initial.txt").readText(), File(a.path, "initial.txt").readText().replace("\r\n", "\n"))
            assertEquals("user new\n", File(a.path, "untracked.txt").readText().replace("\r\n", "\n"))
            File(a.path, "result.txt").writeText("agent result\n")
            val captured = a.copy(resultCommit = port.capture(a))
            assertTrue(port.integrate(workspace, captured))
            assertTrue(port.integrate(workspace, captured))
            port.apply(project, workspace, WorkspaceOperation(lease, "apply-first"))
            port.apply(project, workspace, WorkspaceOperation(lease, "apply-replay"))
            assertEquals("agent result\n", File(source, "result.txt").readText())
            assertEquals(before, git(source, "diff", "--cached"))
            assertEquals(head, git(source, "rev-parse", "HEAD"))
        } finally { port.release(lease) }
    }
    @Test fun independentBranchesMergeAndCurrentUserChangesSurvive() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-data-").toFile(), checks = testGitChecks())
        val w = port.prepare(project, "run")
        val a = port.stage(project, w, attempt("a")); val b = port.stage(project, w, attempt("b"))
        File(a.path, "a.txt").writeText("A"); File(b.path, "b.txt").writeText("B")
        assertTrue(port.integrate(w, a.copy(resultCommit = port.capture(a))))
        assertTrue(port.integrate(w, b.copy(resultCommit = port.capture(b))))
        File(source, "user.txt").writeText("concurrent")
        port.apply(project, w)
        assertEquals("A", File(source, "a.txt").readText())
        assertEquals("B", File(source, "b.txt").readText())
        assertEquals("concurrent", File(source, "user.txt").readText())
    }
    @Test fun conflictingBranchRetainsMergeStateForResolution() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val port = testGitPlanningWorkspace(Files.createTempDirectory("planning-data-").toFile(), checks = testGitChecks())
        val w = port.prepare(project, "run")
        val a = port.stage(project, w, attempt("a")); val b = port.stage(project, w, attempt("b"))
        File(a.path, "initial.txt").writeText("A\n"); File(b.path, "initial.txt").writeText("B\n")
        assertTrue(port.integrate(w, a.copy(resultCommit = port.capture(a))))
        val captured = b.copy(resultCommit = port.capture(b))
        assertFalse(port.integrate(w, captured))
        assertFalse(port.finishConflict(w, captured))
        File(w.integrationPath, "initial.txt").writeText("A and B\n")
        git(File(w.integrationPath), "add", "initial.txt")
        assertTrue(port.finishConflict(w, captured))
    }
    @Test fun secondOwnerCannotAcquireSameProject() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val data = Files.createTempDirectory("planning-lock-").toFile()
        val first = testGitPlanningWorkspace(data, checks = testGitChecks()); val second = testGitPlanningWorkspace(data, checks = testGitChecks())
        val firstLease = assertNotNull(first.acquire(project, "request"))
        assertNull(second.acquire(project, "request"))
        first.release(firstLease)
        second.release(assertNotNull(second.acquire(project, "request")))
    }

    /** Параллельный экземпляр приложения с тем же профилем работает по своим путям;
     * эксклюзивность остаётся только у конкретной канонической папки. */
    @Test fun parallelInstanceAcquiresIndependentPathsButNotTheSamePath() = runTest {
        val a = Files.createTempDirectory("planning-parallel-a-").toFile()
        val b = Files.createTempDirectory("planning-parallel-b-").toFile()
        val data = Files.createTempDirectory("planning-parallel-store-").toFile()
        val first = testGitPlanningWorkspace(data, checks = testGitChecks()); val second = testGitPlanningWorkspace(data, checks = testGitChecks())
        val ownerA = CodingProject("first", "A", a.path, 1)
        val ownerB = ownerA.copy(id = "second", path = b.path)
        val leaseA = assertNotNull(first.acquire(ownerA, "request-a"))
        val leaseB = assertNotNull(second.acquire(ownerB, "request-b"), "Another instance works on its own paths")
        assertNull(second.acquire(ownerA, "parallel-a"), "The same canonical path stays exclusive across processes")
        assertNull(first.acquire(ownerB, "parallel-b"), "The same canonical path stays exclusive across processes")
        first.release(leaseA); second.release(leaseB)
        second.release(assertNotNull(second.acquire(ownerA, "next-a")))
    }

    @Test fun restartAfterPartialTransferCompletesWithoutReapplyingChangedFiles() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val data = Files.createTempDirectory("planning-crash-").toFile()
        var transferred = 0
        val port = testGitPlanningWorkspace(data, checks = testGitChecks()) { event -> if (event.startsWith("transferred:") && ++transferred == 1) error("simulated power loss") }
        val w = port.prepare(project, "run"); val a = port.stage(project, w, attempt("a"))
        File(a.path, "a.txt").writeText("A"); File(a.path, "b.txt").writeText("B")
        assertTrue(port.integrate(w, a.copy(resultCommit = port.capture(a))))
        assertFailsWith<IllegalStateException> { port.apply(project, w) }
        val restarted = testGitPlanningWorkspace(data, checks = testGitChecks())
        assertTrue(restarted.apply(project, w).applied)
        assertEquals("A", File(source, "a.txt").readText()); assertEquals("B", File(source, "b.txt").readText())
    }

    @Test fun changedFileDuringInterruptedTransferIsNeverOverwritten() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val data = Files.createTempDirectory("planning-crash-").toFile()
        val port = testGitPlanningWorkspace(data, checks = testGitChecks()) { event -> if (event == "transfer-prepared") error("simulated crash") }
        val w = port.prepare(project, "run"); val a = port.stage(project, w, attempt("a"))
        File(a.path, "initial.txt").writeText("agent\n")
        assertTrue(port.integrate(w, a.copy(resultCommit = port.capture(a))))
        assertFailsWith<IllegalStateException> { port.apply(project, w) }
        File(source, "initial.txt").writeText("new user edit\n")
        assertFailsWith<IllegalArgumentException> { testGitPlanningWorkspace(data, checks = testGitChecks()).apply(project, w) }
        assertEquals("new user edit\n", File(source, "initial.txt").readText())
    }

    @Test fun interruptedPreparationKeepsTheOriginalSnapshot() = runTest {
        for (boundary in listOf("initial-base-saved", "integration-created")) {
            val source = repository(); val project = CodingProject("p", "P", source.path, 1)
            val data = Files.createTempDirectory("planning-prepare-").toFile()
            val interrupted = testGitPlanningWorkspace(data, checks = testGitChecks()) { if (it == boundary) error("simulated crash") }
            assertFailsWith<IllegalStateException> { interrupted.prepare(project, "run") }
            File(source, "initial.txt").writeText("user edit after crash\n")
            val resumed = testGitPlanningWorkspace(data, checks = testGitChecks())
            val w = resumed.prepare(project, "run")
            assertEquals("initial", File(w.integrationPath, "initial.txt").readText().trim())
            assertEquals(w.baseCommit, git(File(w.integrationPath), "rev-parse", "HEAD"))
            assertEquals("user edit after crash\n", File(source, "initial.txt").readText())
        }
    }

    @Test fun captureCrashRecoversStableCommitReference() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val data = Files.createTempDirectory("planning-capture-").toFile()
        val port = testGitPlanningWorkspace(data, checks = testGitChecks()) { if (it == "captured") error("simulated crash") }
        val w = port.prepare(project, "run"); val a = port.stage(project, w, attempt("a"))
        File(a.path, "result.txt").writeText("result")
        assertFailsWith<IllegalStateException> { port.capture(a) }
        val resumed = testGitPlanningWorkspace(data, checks = testGitChecks())
        assertEquals(resumed.capture(a), resumed.capture(a))
        assertTrue(resumed.integrate(w, a.copy(resultCommit = resumed.capture(a))))
    }
}
