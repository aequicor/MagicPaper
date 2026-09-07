package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GitPlanningWorkspaceTest {
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
        val port = GitPlanningWorkspace(Files.createTempDirectory("planning-data-").toFile())
        assertTrue(port.acquire(project))
        val workspace = port.prepare(project, "run")
        val a = port.stage(project, workspace, attempt("a"))
        assertEquals(File(source, "initial.txt").readText(), File(a.path, "initial.txt").readText().replace("\r\n", "\n"))
        assertEquals("user new\n", File(a.path, "untracked.txt").readText().replace("\r\n", "\n"))
        File(a.path, "result.txt").writeText("agent result\n")
        val captured = a.copy(resultCommit = port.capture(a))
        assertTrue(port.integrate(workspace, captured))
        assertTrue(port.integrate(workspace, captured))
        port.apply(project, workspace)
        port.apply(project, workspace)
        assertEquals("agent result\n", File(source, "result.txt").readText())
        assertEquals(before, git(source, "diff", "--cached"))
        assertEquals(head, git(source, "rev-parse", "HEAD"))
        port.release(project)
    }
    @Test fun independentBranchesMergeAndCurrentUserChangesSurvive() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val port = GitPlanningWorkspace(Files.createTempDirectory("planning-data-").toFile())
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
        val port = GitPlanningWorkspace(Files.createTempDirectory("planning-data-").toFile())
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
        val first = GitPlanningWorkspace(data); val second = GitPlanningWorkspace(data)
        assertTrue(first.acquire(project)); assertFalse(second.acquire(project))
        first.release(project); assertTrue(second.acquire(project)); second.release(project)
    }

    @Test fun restartAfterPartialTransferCompletesWithoutReapplyingChangedFiles() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val data = Files.createTempDirectory("planning-crash-").toFile()
        var transferred = 0
        val port = GitPlanningWorkspace(data) { event -> if (event.startsWith("transferred:") && ++transferred == 1) error("simulated power loss") }
        val w = port.prepare(project, "run"); val a = port.stage(project, w, attempt("a"))
        File(a.path, "a.txt").writeText("A"); File(a.path, "b.txt").writeText("B")
        assertTrue(port.integrate(w, a.copy(resultCommit = port.capture(a))))
        assertFailsWith<IllegalStateException> { port.apply(project, w) }
        val restarted = GitPlanningWorkspace(data)
        assertTrue(restarted.apply(project, w).applied)
        assertEquals("A", File(source, "a.txt").readText()); assertEquals("B", File(source, "b.txt").readText())
    }

    @Test fun changedFileDuringInterruptedTransferIsNeverOverwritten() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val data = Files.createTempDirectory("planning-crash-").toFile()
        val port = GitPlanningWorkspace(data) { event -> if (event == "transfer-prepared") error("simulated crash") }
        val w = port.prepare(project, "run"); val a = port.stage(project, w, attempt("a"))
        File(a.path, "initial.txt").writeText("agent\n")
        assertTrue(port.integrate(w, a.copy(resultCommit = port.capture(a))))
        assertFailsWith<IllegalStateException> { port.apply(project, w) }
        File(source, "initial.txt").writeText("new user edit\n")
        assertFailsWith<IllegalArgumentException> { GitPlanningWorkspace(data).apply(project, w) }
        assertEquals("new user edit\n", File(source, "initial.txt").readText())
    }

    @Test fun interruptedPreparationKeepsTheOriginalSnapshot() = runTest {
        for (boundary in listOf("initial-base-saved", "integration-created")) {
            val source = repository(); val project = CodingProject("p", "P", source.path, 1)
            val data = Files.createTempDirectory("planning-prepare-").toFile()
            val interrupted = GitPlanningWorkspace(data) { if (it == boundary) error("simulated crash") }
            assertFailsWith<IllegalStateException> { interrupted.prepare(project, "run") }
            File(source, "initial.txt").writeText("user edit after crash\n")
            val resumed = GitPlanningWorkspace(data)
            val w = resumed.prepare(project, "run")
            assertEquals("initial", File(w.integrationPath, "initial.txt").readText().trim())
            assertEquals(w.baseCommit, git(File(w.integrationPath), "rev-parse", "HEAD"))
            assertEquals("user edit after crash\n", File(source, "initial.txt").readText())
        }
    }

    @Test fun captureCrashRecoversStableCommitReference() = runTest {
        val source = repository(); val project = CodingProject("p", "P", source.path, 1)
        val data = Files.createTempDirectory("planning-capture-").toFile()
        val port = GitPlanningWorkspace(data) { if (it == "captured") error("simulated crash") }
        val w = port.prepare(project, "run"); val a = port.stage(project, w, attempt("a"))
        File(a.path, "result.txt").writeText("result")
        assertFailsWith<IllegalStateException> { port.capture(a) }
        val resumed = GitPlanningWorkspace(data)
        assertEquals(resumed.capture(a), resumed.capture(a))
        assertTrue(resumed.integrate(w, a.copy(resultCommit = resumed.capture(a))))
    }
}
