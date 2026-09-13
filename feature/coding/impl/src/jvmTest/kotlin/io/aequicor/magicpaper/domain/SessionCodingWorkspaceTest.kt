package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.GitPlanningWorkspace
import io.aequicor.magicpaper.domain.tools.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionCodingWorkspaceTest {
    private class Native(val body: suspend FlowCollector<CodingEvent>.(CodingProject, CodingSession) -> Unit) : CodingRuntime {
        var reconciliation: suspend (String) -> Unit = {}
        override val supported = true
        override val rootPath = "/fixture"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow { body(project, session) }
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun reconcile(sessionId: String) = reconciliation(sessionId)
        override suspend fun uninstall() = Unit
    }

    private class Fixture(val source: File, val workspace: PlanningWorkspace) {
        val f = SessionOrganismTestFixture()
        val service = SessionOrganismService(f.store, f.projects, f.settings) { workspace.verificationSnapshot(it.path) }
        lateinit var tree: SessionTreeRuntime
        suspend fun initialize(native: Native) {
            f.initialize(CodingInteractionMode.CODE)
            f.projects.save(f.project.copy(path = source.path))
            tree = SessionTreeRuntime(service, f.projects, f.profiles, f.settings, clock = { 1_000 }, planningWorkspace = workspace)
            tree.runtime = ToolEnabledCodingRuntime(native, ToolHost(MemoryToolReceiptStore()), tree)
        }
        suspend fun create(id: String, parent: String = "root", tokens: Long = 1_000) {
            val session = f.projects.sessions(f.project.id).single { it.id == parent }
            service.execute(ToolExecutionContext.worker(session), id, "session.create", buildJsonObject {
                put("name", "Implement verified feature"); put("task", "Implement feature")
                put("acceptance", "Record evidence"); put("tokens", tokens)
            })
        }
        suspend fun record(id: String = "session-child") = f.store.get(f.root.organismId!!).sessions.getValue(id).workspace!!
    }

    @Test fun codingChildCapturesAnIsolatedCommitAndPreservesUserCheckoutAndIndex() = runTest { withContext(Dispatchers.Default) {
        val source = repository()
        File(source, "initial.txt").writeText("user staged\n"); git(source, "add", "initial.txt")
        File(source, "initial.txt").appendText("user unstaged\n")
        File(source, "untracked.txt").writeText("user draft\n")
        val head = git(source, "rev-parse", "HEAD"); val index = git(source, "write-tree"); val status = git(source, "status", "--porcelain")
        val port = workspace(); val f = Fixture(source, port)
        f.initialize(Native { project, _ ->
            assertNotEquals(source.canonicalPath, File(project.path).canonicalPath)
            assertEquals("user staged\nuser unstaged\n", File(project.path, "initial.txt").readText())
            assertFalse(port.acquire(project.copy(id = "competing-writer")))
            File(project.path, "result.txt").writeText("verified child result\n")
            emit(CodingEvent.FinalText("Feature verified")); emit(CodingEvent.Finished)
        })
        withTimeout(20_000) { f.tree.withScope(f.f.root) { f.create("child") } }
        val record = f.record()
        assertEquals(SessionCodingWorkspacePhase.CAPTURED, record.phase)
        assertTrue(git(File(record.attempt.path), "symbolic-ref", "--short", "HEAD").startsWith("codex/magicpaper/"))
        assertEquals("verified child result", git(source, "show", "${record.attempt.resultCommit}:result.txt"))
        assertEquals(head, git(source, "rev-parse", "HEAD")); assertEquals(index, git(source, "write-tree"))
        assertEquals(status, git(source, "status", "--porcelain")); assertFalse(File(source, "result.txt").exists())
        val result = f.f.store.get(f.f.root.organismId!!).results.single { it.sessionId == "session-child" }
        assertEquals(record.attempt.resultCommit, result.commitSha)
        assertEquals(record.resultSnapshot, f.service.inspectResult(result))
        val owner = f.f.project.copy(id = "after-completion", path = record.attempt.path)
        assertTrue(port.acquire(owner)); port.release(owner)
        File(record.attempt.path, "result.txt").writeText("later edit")
        assertNull(f.service.inspectResult(result))
    } }

    @Test fun nestedCodingChildSnapshotsItsParentsWorkspaceAndOwnsAnotherWriterLease() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val f = Fixture(source, workspace())
        f.initialize(Native { project, session ->
            if (session.id == "session-child") {
                File(project.path, "parent.txt").writeText("parent change")
                f.create("nested", session.id, tokens = 500)
            } else {
                assertEquals("parent change", File(project.path, "parent.txt").readText())
                assertNotEquals(f.record().attempt.path, project.path)
                File(project.path, "nested.txt").writeText("nested change")
            }
            emit(CodingEvent.FinalText("Evidence")); emit(CodingEvent.Finished)
        })
        withTimeout(30_000) { f.tree.withScope(f.f.root) { f.create("child") } }
        val parent = f.record(); val nested = f.record("session-nested")
        assertEquals(SessionCodingWorkspacePhase.CAPTURED, parent.phase)
        assertEquals(SessionCodingWorkspacePhase.CAPTURED, nested.phase)
        assertEquals("nested change", git(source, "show", "${nested.attempt.resultCommit}:nested.txt"))
        assertFalse(File(parent.attempt.path, "nested.txt").exists())
        assertFalse(File(source, "parent.txt").exists()); assertFalse(File(source, "nested.txt").exists())
    } }

    @Test fun nativeReconciliationMustCompleteBeforeCommitAndWriterRelease() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val port = workspace(); val f = Fixture(source, port)
        val reconciling = CompletableDeferred<Unit>(); val reconciled = CompletableDeferred<Unit>()
        val native = Native { project, _ ->
            File(project.path, "result.txt").writeText("finished model")
            emit(CodingEvent.FinalText("Evidence")); emit(CodingEvent.Finished)
        }
        native.reconciliation = { id -> if (id == "session-child") { reconciling.complete(Unit); reconciled.await() } }
        f.initialize(native)
        val run = async { f.tree.withScope(f.f.root) { f.create("child") } }
        try {
            withTimeout(20_000) { reconciling.await() }
            val record = f.record()
            assertEquals(SessionCodingWorkspacePhase.RUNNING, record.phase)
            assertTrue(record.attempt.resultCommit.isBlank()); assertFalse(run.isCompleted)
            assertFalse(port.acquire(f.f.project.copy(id = "too-early", path = record.attempt.path)))
            reconciled.complete(Unit)
            withTimeout(20_000) { run.await() }
            assertEquals(SessionCodingWorkspacePhase.CAPTURED, f.record().phase)
        } finally { reconciled.complete(Unit); run.cancelAndJoin() }
    } }

    @Test fun captureFailureAfterGitEffectStaysUnknownAndDoesNotApplyOrReplay() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); var captures = 0
        val port = workspace { if (it == "captured") { captures++; error("fault after commit ref") } }
        val f = Fixture(source, port)
        f.initialize(Native { project, _ ->
            File(project.path, "result.txt").writeText("durable Git effect")
            emit(CodingEvent.FinalText("Evidence")); emit(CodingEvent.Finished)
        })
        withTimeout(20_000) { f.tree.withScope(f.f.root) { f.create("child") } }
        assertEquals(1, captures)
        val node = f.f.store.get(f.f.root.organismId!!).sessions.getValue("session-child")
        assertEquals(SessionObservedState.UNKNOWN, node.observed)
        assertEquals(SessionCodingWorkspacePhase.UNKNOWN, node.workspace!!.phase)
        assertTrue(node.workspace!!.attempt.resultCommit.isBlank())
        assertFalse(File(source, "result.txt").exists())
    } }

    @Test fun nonGitChildFailsBeforeNativeWorkAndNeverInitializesRepository() = runTest { withContext(Dispatchers.Default) {
        val source = Files.createTempDirectory("session-no-git-").toFile(); File(source, "user.txt").writeText("keep")
        val f = Fixture(source, workspace()); var started = false
        f.initialize(Native { _, _ -> started = true; emit(CodingEvent.Finished) })
        withTimeout(20_000) { f.tree.withScope(f.f.root) { f.create("child") } }
        assertFalse(started); assertFalse(File(source, ".git").exists())
        assertEquals("keep", File(source, "user.txt").readText())
        assertNotEquals(SessionCodingWorkspacePhase.RUNNING, f.record().phase)
    } }

    @Test fun sourceChangingDuringCommitCannotAcquireEvidenceForAnotherTree() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val gitWorkspace = workspace()
        val port = object : PlanningWorkspace by gitWorkspace {
            override suspend fun capture(attempt: StageAttempt): String = gitWorkspace.capture(attempt).also {
                File(attempt.path, "result.txt").writeText("concurrent edit after capture")
            }
        }
        val f = Fixture(source, port)
        f.initialize(Native { project, _ ->
            File(project.path, "result.txt").writeText("captured content")
            emit(CodingEvent.FinalText("Evidence")); emit(CodingEvent.Finished)
        })
        withTimeout(20_000) { f.tree.withScope(f.f.root) { f.create("child") } }
        assertEquals(SessionCodingWorkspacePhase.UNKNOWN, f.record().phase)
        assertTrue(f.record().attempt.resultCommit.isBlank())
        assertFalse(File(source, "result.txt").exists())
    } }

    @Test fun unconfirmedNativeStopRetainsWriterUntilExplicitReconciliation() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val port = workspace(); val f = Fixture(source, port)
        var terminationConfirmed = false
        val native = Native { project, _ ->
            File(project.path, "result.txt").writeText("preserved work")
            emit(CodingEvent.FinalText("Evidence")); emit(CodingEvent.Finished)
        }
        native.reconciliation = { id -> check(id != "session-child" || terminationConfirmed) { "native stop uncertain" } }
        f.initialize(native)
        withTimeout(20_000) { f.tree.withScope(f.f.root) { f.create("child") } }
        val record = f.record(); val writer = f.f.project.copy(id = "reconciled-writer", path = record.attempt.path)
        assertEquals(SessionCodingWorkspacePhase.UNKNOWN, record.phase)
        assertFalse(port.acquire(writer))
        terminationConfirmed = true
        f.service.stopSubtree(setOf("session-child"))
        assertEquals(SessionCodingWorkspacePhase.STOPPED, f.record().phase)
        assertTrue(port.acquire(writer)); port.release(writer)
        assertEquals("preserved work", File(record.attempt.path, "result.txt").readText())
        assertFalse(File(source, "result.txt").exists())
    } }

    @Test fun streamWithoutFinishedCannotCaptureACompletedCodingResult() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val f = Fixture(source, workspace())
        f.initialize(Native { project, _ ->
            File(project.path, "result.txt").writeText("partial work")
            emit(CodingEvent.FinalText("Unconfirmed answer"))
        })
        withTimeout(20_000) { f.tree.withScope(f.f.root) { f.create("child") } }
        assertEquals(SessionCodingWorkspacePhase.STOPPED, f.record().phase)
        assertEquals(SessionObservedState.FAILED, f.f.store.get(f.f.root.organismId!!).sessions.getValue("session-child").observed)
        assertTrue(f.record().attempt.resultCommit.isBlank())
        assertFalse(File(source, "result.txt").exists())
    } }

    @Test fun directCodeRootSharesWriterGateAndHoldsItThroughNativeCleanup() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val port = workspace(); val f = Fixture(source, port)
        val started = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        val nativeSessions = MutableStateFlow(emptySet<String>())
        f.initialize(Native { project, session ->
            nativeSessions.update { it + session.id }
            if (session.id == "root") {
                assertEquals(source.path, project.path)
                // Its source read uses the root's existing writer lease; the actual child
                // still receives a different acquired workspace.
                f.create("child")
                f.service.waitChildren(setOf("session-child"))
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); cleaned.await() } }
            } else {
                assertNotEquals(source.path, project.path)
                emit(CodingEvent.FinalText("Child verified")); emit(CodingEvent.Finished)
            }
        })
        val project = f.f.project.copy(path = source.path)
        val other = f.f.root.copy(id = "other-root", organismId = null, runtimeGeneration = 0)
        f.f.projects.saveSession(other)
        val run = launch { f.tree.runtime!!.run(project, f.f.root, "Work", null).collect() }
        try {
            withTimeout(20_000) { started.await() }
            assertFailsWith<IllegalArgumentException> { f.tree.runtime!!.run(project, other, "Other work", null).collect() }
            assertFalse("other-root" in nativeSessions.value)
            assertEquals(SessionCodingWorkspacePhase.CAPTURED, f.record().phase)
            run.cancel(); withTimeout(5_000) { cleaning.await() }
            assertFalse(run.isCompleted)
            assertFalse(port.acquire(project.copy(id = "during-cleanup")))
            cleaned.complete(Unit); withTimeout(10_000) { run.join() }
            val owner = project.copy(id = "after-cleanup")
            assertTrue(port.acquire(owner)); port.release(owner)
        } finally { cleaned.complete(Unit); run.cancelAndJoin() }
    } }

    @Test fun unknownDirectRootKeepsItsSourceLeaseUntilExplicitStopReconciles() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val port = workspace(); val f = Fixture(source, port)
        var confirmed = false
        val native = Native { _, _ -> emit(CodingEvent.FinalText("Answer")); emit(CodingEvent.Finished) }
        native.reconciliation = { check(confirmed) { "native stop uncertain" } }
        f.initialize(native)
        val project = f.f.project.copy(path = source.path)
        f.tree.runtime!!.run(project, f.f.root, "Work", null).collect()
        assertEquals(SessionObservedState.UNKNOWN, f.f.store.get(f.f.root.organismId!!).sessions.getValue("root").observed)
        val owner = project.copy(id = "after-reconciliation")
        assertFalse(port.acquire(owner))
        confirmed = true
        f.service.stopSubtree(setOf("root"))
        assertEquals(SessionObservedState.STOPPED, f.f.store.get(f.f.root.organismId!!).sessions.getValue("root").observed)
        assertTrue(port.acquire(owner)); port.release(owner)
    } }

    private fun workspace(checkpoint: (String) -> Unit = {}) = GitPlanningWorkspace(Files.createTempDirectory("session-workspace-").toFile(), checkpoint)
    private fun repository(): File = Files.createTempDirectory("session-source-").toFile().also { source ->
        git(source, "init"); File(source, "initial.txt").writeText("initial\n")
        git(source, "add", "."); git(source, "commit", "-m", "Initial source")
    }
    private fun git(directory: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@localhost") + arguments)
            .directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }; return output.trim()
    }
}
