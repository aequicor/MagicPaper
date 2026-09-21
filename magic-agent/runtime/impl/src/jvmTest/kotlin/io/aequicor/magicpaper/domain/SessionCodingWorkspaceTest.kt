package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.coding.createTestSession

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
        val f = SessionOrganismTestFixture(project = CodingProject("project", "Project", source.path, 1))
        val service = testOrganismService(f.store, f.projects, f.settings, ports = f.ports) { workspace.verificationSnapshot(it.path) }
        lateinit var tree: SessionTreeRuntime
        suspend fun initialize(native: Native) {
            f.initialize(CodingInteractionMode.CODE)
            tree = testSessionTree(service, f.projects, f.profiles, f.settings, f.ports, clock = { 1_000 }, planningWorkspace = workspace).also { f.ports.tree = it }
            f.ports.nativeRuntime = testToolRuntime(native, testToolSessions(MemoryToolReceiptStore()), tree)
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
            assertNull(port.acquire(project.copy(id = "competing-writer"), "lease-request-1"))
            File(project.path, "result.txt").writeText("verified child result\n")
            emit(CodingEvent.FinalText("Feature verified")); emit(CodingEvent.Finished)
        })
        withTimeout(20_000) { f.tree.withScope(f.f.root) { f.create("child") } }
        val record = f.record()
        assertEquals(SessionCodingWorkspacePhase.CAPTURED, record.phase)
        assertTrue(git(File(record.attempt.path), "symbolic-ref", "--short", "HEAD").startsWith("magicpaper/"))
        assertEquals("verified child result", git(source, "show", "${record.attempt.resultCommit}:result.txt"))
        assertEquals(head, git(source, "rev-parse", "HEAD")); assertEquals(index, git(source, "write-tree"))
        assertEquals(status, git(source, "status", "--porcelain")); assertFalse(File(source, "result.txt").exists())
        val result = f.f.store.get(f.f.root.organismId!!).results.single { it.sessionId == "session-child" }
        assertEquals(record.attempt.resultCommit, result.commitSha)
        assertEquals(record.resultSnapshot, f.tree.sources.inspect(result))
        val owner = f.f.project.copy(id = "after-completion", path = record.attempt.path)
        val heldWorkspace2 = assertNotNull(port.acquire(owner, "lease-request-2")); port.release(heldWorkspace2)
        File(record.attempt.path, "result.txt").writeText("later edit")
        assertNull(f.tree.sources.inspect(result))
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
            assertNull(port.acquire(f.f.project.copy(id = "too-early", path = record.attempt.path), "lease-request-3"))
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
            override suspend fun capture(attempt: StageAttempt, operation: WorkspaceOperation): String = gitWorkspace.capture(attempt, operation).also {
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
        assertNull(port.acquire(writer, "lease-request-4"))
        terminationConfirmed = true
        f.service.stopSubtree(setOf("session-child"))
        assertEquals(SessionCodingWorkspacePhase.STOPPED, f.record().phase)
        val heldWorkspace5 = assertNotNull(port.acquire(writer, "lease-request-5")); port.release(heldWorkspace5)
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
                f.tree.executions.await(setOf("session-child"))
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); cleaned.await() } }
            } else {
                assertNotEquals(source.path, project.path)
                emit(CodingEvent.FinalText("Child verified")); emit(CodingEvent.Finished)
            }
        })
        val project = f.f.project.copy(path = source.path)
        val other = f.f.root.copy(id = "other-root", organismId = null, runtimeGeneration = 0)
        f.f.projects.createTestSession(other)
        // Живой прогон держит папку: соседний запуск ждёт ограниченное время и получает действенную ошибку.
        f.f.ports.rootLeaseWaitMillis = 250
        val run = launch { f.tree.runtime!!.run(project, f.f.root, "Work", null).collect() }
        try {
            withTimeout(20_000) { started.await() }
            assertFailsWith<TaskWorkspaceBusy> { f.tree.runtime!!.run(project, other, "Other work", null).collect() }
            assertFalse("other-root" in nativeSessions.value)
            assertEquals(SessionCodingWorkspacePhase.CAPTURED, f.record().phase)
            run.cancel(); withTimeout(5_000) { cleaning.await() }
            assertFalse(run.isCompleted)
            assertNull(port.acquire(project.copy(id = "during-cleanup"), "lease-request-6"))
            cleaned.complete(Unit); withTimeout(10_000) { run.join() }
            val owner = project.copy(id = "after-cleanup")
            val heldWorkspace7 = assertNotNull(port.acquire(owner, "lease-request-7")); port.release(heldWorkspace7)
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
        assertNull(port.acquire(owner, "lease-request-8"))
        confirmed = true
        f.service.stopSubtree(setOf("root"))
        assertEquals(SessionObservedState.STOPPED, f.f.store.get(f.f.root.organismId!!).sessions.getValue("root").observed)
        val heldWorkspace9 = assertNotNull(port.acquire(owner, "lease-request-9")); port.release(heldWorkspace9)
    } }

    @Test fun staleSourceLeaseYieldsOnlyToProvenNativeStop() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val port = workspace(); val f = Fixture(source, port)
        var confirmed = false
        val native = Native { _, _ -> emit(CodingEvent.FinalText("Answer")); emit(CodingEvent.Finished) }
        native.reconciliation = { check(confirmed) { "native stop uncertain" } }
        f.initialize(native)
        val project = f.f.project.copy(path = source.path)
        f.tree.runtime!!.run(project, f.f.root, "Work", null).collect()
        val next = project.copy(id = "task-source-next")
        assertNull(port.acquire(next, "lease-request-10"), "Неподтверждённая остановка держит исходную папку")
        // Задача не получает доступ к папке без доказательства остановки чужого исполнителя.
        assertFalse(f.tree.releaseUnownedRootLeases())
        assertNull(port.acquire(next, "lease-request-11"))
        confirmed = true
        assertTrue(f.tree.releaseUnownedRootLeases())
        val heldWorkspace12 = assertNotNull(port.acquire(next, "lease-request-12")); port.release(heldWorkspace12)
        assertFalse(f.tree.releaseUnownedRootLeases(), "Освобождённое удержание не возвращается")
    } }

    @Test fun failedRootRestoreRetriesItsOwnStaleLeaseReleaseInsteadOfLooping() = runTest { withContext(Dispatchers.Default) {
        val source = repository()
        // Три сбоя освобождения: прогон, сверка при восстановлении и повтор при запуске. Дальше ожидание
        // повторяет освобождение само и доводит восстановление до конца вместо вечного «занятой папки».
        var releaseFaults = 3
        val port = workspace { if (it == "project-lock-releasing" && releaseFaults-- > 0) error("injected release failure") }
        val f = Fixture(source, port)
        var confirmed = false
        val native = Native { _, _ -> emit(CodingEvent.FinalText("Answer")); emit(CodingEvent.Finished) }
        native.reconciliation = { check(confirmed) { "native stop uncertain" } }
        f.initialize(native)
        f.f.ports.rootLeaseWaitMillis = 5_000
        val project = f.f.project.copy(path = source.path)
        suspend fun node() = f.f.store.get(f.f.root.organismId!!).sessions.getValue("root")
        // Первый прогон завершён без подтверждения остановки: папка удержана, состояние UNKNOWN.
        f.tree.runtime!!.run(project, f.f.root, "Work", null).collect()
        assertEquals(SessionObservedState.UNKNOWN, node().observed)
        confirmed = true
        // Восстановление повторяет освобождение внутри ожидания и завершается без ручного повтора.
        withTimeout(20_000) { f.tree.runtime!!.run(project, f.f.root, "Continue", null).collect() }
        assertEquals(SessionObservedState.COMPLETED, node().observed)
        val checker = project.copy(id = "after-recovery")
        val heldWorkspace13 = assertNotNull(port.acquire(checker, "lease-request-13")); port.release(heldWorkspace13)
    } }

    @Test fun newRootSessionReclaimsStaleLeaseOfAGoneSessionInsteadOfFailing() = runTest { withContext(Dispatchers.Default) {
        val source = repository(); val port = workspace(); val f = Fixture(source, port)
        var confirmed = false
        val native = Native { _, _ -> emit(CodingEvent.FinalText("Answer")); emit(CodingEvent.Finished) }
        native.reconciliation = { check(confirmed) { "native stop uncertain" } }
        f.initialize(native)
        val project = f.f.project.copy(path = source.path)
        f.tree.runtime!!.run(project, f.f.root, "Work", null).collect()
        assertEquals(SessionObservedState.UNKNOWN, f.f.store.get(f.f.root.organismId!!).sessions.getValue("root").observed)
        confirmed = true
        val other = f.f.root.copy(id = "other-root", organismId = null, runtimeGeneration = 0)
        f.f.projects.createTestSession(other)
        // Сессия-владелец удержания больше не работает: соседний запуск снимает его сверкой движка,
        // а не отказывается навсегда «занятой папкой».
        withTimeout(20_000) { f.tree.runtime!!.run(project, other, "Other work", null).collect() }
        assertEquals(SessionObservedState.COMPLETED,
            f.f.store.organisms.value.values.single { "other-root" in it.sessions }.sessions.getValue("other-root").observed)
        val checker = project.copy(id = "after-reclaim")
        val heldWorkspace14 = assertNotNull(port.acquire(checker, "lease-request-14")); port.release(heldWorkspace14)
    } }

    private fun workspace(checkpoint: (String) -> Unit = {}) = testGitPlanningWorkspace(Files.createTempDirectory("session-workspace-").toFile(), checks = testGitChecks(), checkpoint = checkpoint)
    private fun repository(): File = Files.createTempDirectory("session-source-").toFile().also { source ->
        git(source, "init")
        // Изолированные копии чекаутит настоящий Git: глобальный core.autocrlf разработчика не должен решать байты.
        git(source, "config", "core.autocrlf", "false")
        File(source, "initial.txt").writeText("initial\n")
        git(source, "add", "."); git(source, "commit", "-m", "Initial source")
    }
    private fun git(directory: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@localhost") + arguments)
            .directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }; return output.trim()
    }
}
