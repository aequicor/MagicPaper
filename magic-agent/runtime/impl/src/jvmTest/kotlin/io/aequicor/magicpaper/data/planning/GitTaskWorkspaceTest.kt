package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.checks.createCommandChecks
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolRole
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import io.aequicor.magicpaper.domain.checks.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import kotlin.test.*

class GitTaskWorkspaceTest {
    private class Fixture {
        val root = Files.createTempDirectory("magicpaper-task-test-").toFile()
        val source = File(root, "source").apply { mkdirs() }
        val pool = File(root, "pool")
        val project = CodingProject("p", "Project", source.path, 1)
        var leaseCheckpoint: (String) -> Unit = {}
        val gitChecks = testGitChecks()
        val authority = GitWorkspaceAuthority(gitChecks, File(root, "leases")) { leaseCheckpoint(it) }
        private val registries = mutableMapOf<GitTaskWorkspace, GitWorkspaceAuthority>()
        fun port(checks: CommandChecks = gitChecks, checkpoint: (String) -> Unit = {}): GitTaskWorkspace {
            val registry = if (checks === gitChecks) authority else GitWorkspaceAuthority(checks, File(root, "leases"))
            return GitTaskWorkspace(registry, pool, checkpoint).also { registries[it] = registry }
        }
        val port = port()
        val journal = InMemoryEventJournal()
        val payloads = InMemoryKeyValueStore()
        // This suite tests the Git child with a controlled parent projection port. App tests use CodingMachine.
        fun worktreeService(repo: JsonCodingProjectRepository, workspace: TaskWorkspace, leases: PlanningWorkspace): TaskWorktreeService =
            TaskWorktreeService(object : TaskWorktreeSessionAccess {
                override suspend fun session(projectId: String, sessionId: String) = repo.sessions(projectId).firstOrNull { it.id == sessionId }
                override suspend fun publish(projection: TaskWorktreeProjection) {
                    repo.updateSession(projection.owner.projectId, projection.owner.sessionId) { session ->
                        check(session.runtimeGeneration == projection.generation)
                        session.copy(taskWorktree = projection.task)
                    }
                }
            }, workspace, leases, testTaskWorktreeOwner(workspace, journal, payloads), TestTaskWorktreeRuntime())

        init {
            git(source, "init", "-b", "main")
            // Delivery checks out files with the real Git; the developer's global core.autocrlf must not decide their bytes.
            git(source, "config", "core.autocrlf", "false")
            source.resolve("base.txt").writeText("base\n")
            git(source, "add", "."); git(source, "commit", "-m", "base")
        }
        fun registry(port: GitTaskWorkspace) = registries.getValue(port)
        private suspend fun <T> GitTaskWorkspace.operation(record: TaskWorktree, kind: TaskWorktreeMachine.Operation,
            id: String = UUID.randomUUID().toString(), action: suspend (TaskWorkspaceOperation) -> T): T {
            val registry = registry(this)
            val execution = checkNotNull(registry.acquire(project.copy(id = "task-$id", path = record.path), id))
            var source: WorkspaceLease? = null
            var failure: Throwable? = null
            try {
                if (kind in setOf(TaskWorktreeMachine.Operation.OPEN, TaskWorktreeMachine.Operation.DELIVER))
                    source = checkNotNull(registry.acquire(project.copy(id = "source-$id", path = record.sourcePath), id))
                val pending = TaskWorktreeMachine.Pending(id, kind, record.taskId, 0, record)
                return action(TaskWorkspaceOperation(TaskWorktreeOwnerId(project.id, "session"), pending, TaskWorkspaceLeases(source, execution)))
            } catch (error: Throwable) { failure = error; throw error }
            finally { withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                for (lease in listOfNotNull(source, execution)) try { registry.release(lease) } catch (cleanup: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = cleanup else if (first !== cleanup) first.addSuppressed(cleanup)
                }
                cleanupFailure?.let { cleanup ->
                    val primary = failure
                    if (primary == null) throw cleanup
                    if (cleanup !== primary) primary.addSuppressed(cleanup)
                }
            } }
        }
        suspend fun GitTaskWorkspace.open(record: TaskWorktree, previous: TaskWorktree? = null) = operation(record, TaskWorktreeMachine.Operation.OPEN) { open(record, it, previous) }
        suspend fun GitTaskWorkspace.capture(record: TaskWorktree) = operation(record, TaskWorktreeMachine.Operation.CAPTURE) { capture(record, it) }
        suspend fun GitTaskWorkspace.refresh(record: TaskWorktree) = operation(record, TaskWorktreeMachine.Operation.REFRESH) { refresh(record, it) }
        suspend fun GitTaskWorkspace.integrate(record: TaskWorktree) = operation(record, TaskWorktreeMachine.Operation.INTEGRATE) { integrate(record, it) }
        suspend fun GitTaskWorkspace.verify(record: TaskWorktree, operationId: String) = operation(record, TaskWorktreeMachine.Operation.VERIFY, operationId) { verify(record, it) }
        suspend fun GitTaskWorkspace.deliver(record: TaskWorktree) = operation(record, TaskWorktreeMachine.Operation.DELIVER) { deliver(record, it) }

        suspend fun open(id: String = "one"): TaskWorktree = port.describe(project, "session", id, "Task $id").also { port.open(it) }
        suspend fun prepare(record: TaskWorktree): TaskWorktree {
            val captured = record.copy(resultCommit = port.capture(record), targetCommit = port.target(record))
            return captured.copy(mergeCommit = checkNotNull(port.integrate(captured)))
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val f = Fixture()
        try { f.block() } finally { f.root.deleteRecursively() }
    }

    @Test fun unchangedTaskDoesNotInventCommit() = runTest { fixture {
        val before = git(source, "rev-parse", "HEAD")
        val record = prepare(open())
        port.deliver(record)
        assertEquals(before, git(source, "rev-parse", "HEAD"))
        assertTrue(port.delivered(record))
    } }

    @Test fun resultWithUninitializedSubmoduleCompletesVerificationAndDelivery() = runTest { fixture {
        val submoduleCommit = git(source, "rev-parse", "HEAD")
        source.resolve("tools/mission-visualization").mkdirs()
        git(source, "update-index", "--add", "--cacheinfo", "160000,$submoduleCommit,tools/mission-visualization")
        git(source, "commit", "-m", "submodule")
        val kv = InMemoryKeyValueStore()
        val repo = JsonCodingProjectRepository(kv, Json { encodeDefaults = true })
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        val service = worktreeService(repo, port, GitPlanningWorkspace(File(root, "leases"), authority))
        val task = service.begin(project, "session", "request")
        File(task.path).resolve("result.txt").writeText("finished")
        service.handoff(ToolExecutionContext(project.id, "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.CODE), true, emptyList())
        val complete = service.complete(project, "session", "request", repair = { error("unexpected repair") })
        assertEquals(TaskWorktreePhase.COMPLETE, complete.phase)
        assertEquals("finished", source.resolve("result.txt").readText())
        assertEquals(submoduleCommit, git(source, "rev-parse", "HEAD:tools/mission-visualization"))
        assertTrue(port.delivered(complete))
    } }

    @Test fun blockedHandoffKeepsItsReasonAndDoesNotDeliver() = runTest { fixture {
        val repo = JsonCodingProjectRepository(InMemoryKeyValueStore(), Json { encodeDefaults = true })
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        val service = worktreeService(repo, port, GitPlanningWorkspace(File(root, "leases"), authority))
        val task = service.begin(project, "session", "request")
        File(task.path).resolve("result.txt").writeText("incomplete")
        service.handoff(ToolExecutionContext(project.id, "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.CODE), false, emptyList())
        val blocked = repo.sessions(project.id).single().taskWorktree!!
        val failure = assertFailsWith<IllegalStateException> { service.complete(project, "session", "request", repair = {}) }
        assertEquals(blocked.error, failure.message)
        assertFalse(source.resolve("result.txt").exists())
        assertEquals(TaskWorktreePhase.RUNNING, blocked.phase)
    } }

    @Test fun planBorrowsTheExactExecutionLeaseAndDeliveryKeepsItHeld() = runTest { fixture {
        val repo = JsonCodingProjectRepository(InMemoryKeyValueStore(), Json { encodeDefaults = true })
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        val leases = GitPlanningWorkspace(File(root, "leases"), authority)
        val service = worktreeService(repo, port, leases)
        val task = service.begin(project, "session", "request")
        File(task.path, "result.txt").writeText("finished")
        val borrowed = checkNotNull(leases.acquire(project.copy(id = "planner", path = task.path), "plan-request"))
        try {
            val result = service.complete(project, "session", "request", planAccepted = true,
                executionLease = borrowed, repair = { error("Unexpected repair") })
            assertEquals(TaskWorktreePhase.COMPLETE, result.phase)
            assertEquals("finished", source.resolve("result.txt").readText())
            assertEquals(borrowed.ownerId, leases.holderOf(task.path))
            assertNull(leases.holderOf(source.path))
        } finally { leases.release(borrowed) }
    } }

    @Test fun repositorySubdirectoryAcquiresTheDescribedSourceRoot() = runTest { fixture {
        val selected = project.copy(path = File(source, "selected").apply { mkdirs() }.path)
        val repo = JsonCodingProjectRepository(InMemoryKeyValueStore(), Json { encodeDefaults = true })
        repo.save(selected)
        repo.saveSession(CodingSession("session", project.id, "Task", 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        val leases = GitPlanningWorkspace(File(root, "leases"), authority)
        val service = worktreeService(repo, port, leases)
        assertTrue(service.availability(selected).available)
        val task = service.begin(selected, "session", "request")
        assertEquals(source.canonicalPath, task.sourcePath)
        assertEquals(TaskWorktreePhase.RUNNING, task.phase)
        assertNull(leases.holderOf(source.path))
        assertNull(leases.holderOf(task.path))
    } }

    @Test fun sourceChangeBetweenDescriptionAndLeaseCannotCreateOrDeliverTask() = runTest { fixture {
        val repo = JsonCodingProjectRepository(InMemoryKeyValueStore(), Json { encodeDefaults = true })
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        val real = GitPlanningWorkspace(File(root, "leases"), authority)
        var changed = false
        val leases = object : PlanningWorkspace by real {
            override suspend fun acquire(project: CodingProject, requestId: String): WorkspaceLease? {
                if (!changed && project.id.startsWith("task-source-")) {
                    changed = true
                    source.resolve("user.txt").writeText("new source")
                    git(source, "add", "."); git(source, "commit", "-m", "user update")
                }
                return real.acquire(project, requestId)
            }
        }
        val service = worktreeService(repo, port, leases)
        assertFailsWith<IllegalStateException> { service.begin(project, "session", "request") }
        assertTrue(changed)
        assertEquals("new source", source.resolve("user.txt").readText())
        assertEquals("", git(source, "branch", "--list", "magicpaper/worktree-*"))
        assertTrue(!pool.exists() || pool.listFiles().orEmpty().isEmpty())
        assertNull(real.holderOf(source.path))
    } }

    @Test fun targetBranchWithPlusSupportsRefreshIntegrationAndDelivery() = runTest { fixture {
        git(source, "switch", "-c", "release+fix")
        var task = open()
        source.resolve("upstream.txt").writeText("upstream")
        git(source, "add", "."); git(source, "commit", "-m", "upstream")
        val refreshed = port.refresh(task)
        assertTrue(refreshed.updated)
        task = task.copy(integratedCommit = refreshed.targetCommit)
        File(task.path, "result.txt").writeText("result")
        val result = prepare(task)
        port.verify(result, "plus-branch-verification")
        port.deliver(result)
        assertEquals("result", source.resolve("result.txt").readText())
        assertEquals("release+fix", git(source, "symbolic-ref", "--short", "HEAD"))
        assertTrue(port.delivered(result))
    } }

    @Test fun sourceIsUntouchedUntilDeliveryAndAgentCommitsSurvive() = runTest { fixture {
        val task = open()
        val dir = File(task.path)
        dir.resolve("agent.txt").writeText("committed")
        git(dir, "add", "."); git(dir, "commit", "-m", "agent commit")
        val agent = git(dir, "rev-parse", "HEAD")
        dir.resolve("remaining.txt").writeText("pending")
        val result = prepare(task)
        assertFalse(source.resolve("agent.txt").exists())
        assertEquals(task.baseCommit, git(source, "rev-parse", "HEAD"))
        port.verify(result, "test-verification-1")
        port.deliver(result)
        assertEquals("pending", source.resolve("remaining.txt").readText())
        assertEquals(agent, git(source, "rev-parse", "HEAD^"))
        assertEquals("", git(source, "status", "--porcelain"))
    } }

    @Test fun verifyRunsTaskChecksInManagedCopyAndReportsTheirOutput() = runTest { fixture {
        val checked = port()
        val task = open()
        val dir = File(task.path)
        dir.resolve("result.txt").writeText("ok")
        git(dir, "add", "."); git(dir, "commit", "-m", "result")
        val record = prepare(task)
        checked.verify(record.copy(checks = listOf(listOf("git", "--version"))), "test-verification-2")
        val failure = assertFailsWith<IllegalStateException> {
            checked.verify(record.copy(checks = listOf(listOf("git", "rev-parse", "--verify", "refs/heads/missing-branch"))), "test-verification-3")
        }
        assertTrue(failure.message!!.startsWith("Проверка результата завершилась с ошибкой"), failure.message)
        assertTrue(failure.message!!.contains("fatal"), failure.message)
        assertEquals(record.mergeCommit, git(dir, "rev-parse", "HEAD"))
        assertEquals("", git(dir, "status", "--porcelain", "--untracked-files=all"))
    } }

    @Test fun failedTestIdentitySurvivesWarningsFromParallelBuildTasks() = runTest { fixture {
        val output = "PaperActivityIndicatorTest > pulseIsSharedByBothSilhouettes FAILED\n" +
            "w: unrelated compilation warning\n".repeat(300) + "BUILD FAILED in 16s"
        val runner = object : CommandChecks by gitChecks {
            private val results = mutableMapOf<CheckRef, CheckResult>()
            override suspend fun run(command: CheckCommand): CheckResult =
                if (command.outputMode == CheckOutputMode.TEXT) CheckResult(output, 1).also { results[command.ref] = it }
                else gitChecks.run(command)
            override suspend fun inspect(ref: CheckRef) = results[ref] ?: gitChecks.inspect(ref)
        }
        val checked = port(runner)
        val task = open()
        File(task.path, "result.txt").writeText("pending result")
        val record = prepare(task)
        val failure = assertFailsWith<IllegalStateException> {
            checked.verify(record.copy(checks = listOf(listOf("./gradlew", "test"))), "test-verification-4")
        }
        assertContains(failure.message.orEmpty(), "PaperActivityIndicatorTest > pulseIsSharedByBothSilhouettes FAILED")
        assertContains(failure.message.orEmpty(), "BUILD FAILED in 16s")
        assertTrue(failure.message.orEmpty().length < 2200, "Diagnostic remains bounded")
        assertEquals(record.baseCommit, git(source, "rev-parse", "HEAD"), "A failed check never delivers the task")
    } }

    @Test fun checkCancellationPreservesUnknownLeaseWithoutDelivery() = runTest { fixture {
        val cancellation = CancellationException("Check cancelled")
        var cancelledRef: CheckRef? = null
        val runner = object : CommandChecks by gitChecks {
            override suspend fun run(command: CheckCommand): CheckResult {
                if (command.outputMode != CheckOutputMode.TEXT) return gitChecks.run(command)
                cancelledRef = command.ref
                throw cancellation
            }
        }
        val checked = port(runner)
        val record = prepare(open())
        val failure = assertFailsWith<CancellationException> {
            checked.verify(record.copy(checks = listOf(listOf("check"))), "cancelled-verification")
        }
        assertTrue(failure === cancellation || failure.cause === cancellation)
        assertNotNull(cancelledRef)
        assertNotNull(registry(checked).holderOf(record.path), "An unknown cancelled check retains the exact task lease")
        assertEquals(record.baseCommit, git(source, "rev-parse", "HEAD"))
    } }

    @Test fun dirtyIndexAndUntrackedFilesBlockPreparation() = runTest { fixture {
        source.resolve("untracked").writeText("user")
        val unstaged = assertFailsWith<IllegalArgumentException> { port.describe(project, "s", "r", "label") }
        // Отказ обязан назвать папку и путь: иначе пользователь проверяет свой Git и не находит изменений.
        assertContains(unstaged.message.orEmpty(), "исходная папка проекта")
        assertContains(unstaged.message.orEmpty(), "?? untracked")
        git(source, "add", "untracked")
        val staged = assertFailsWith<IllegalArgumentException> { port.describe(project, "s", "r", "label") }
        assertContains(staged.message.orEmpty(), "A  untracked")
        assertEquals("user", source.resolve("untracked").readText())
    } }

    /**
     * Настоящая ОС: доставка идёт через штатного владельца проверок, то есть git-команды приложения
     * получают его окружение (песочницу для чтения и закалённый конфиг для записи). Фикстура выше
     * подменяет владельца файловым протоколом, поэтому без этого теста запись через реальную
     * песочницу не проверена: `-Pmagicpaper.research.native=true`.
     */
    @Test fun realCheckOwnerDeliversATaskThroughNativeGit() = runTest {
        assumeTrue(System.getProperty("magicpaper.research.native") == "true")
        fixture {
            val checks = createCommandChecks(InMemoryEventJournal(), InMemoryKeyValueStore(),
                File(root, "native-checks").toPath(), 120_000)
            try {
                val real = port(checks)
                var task = real.describe(project, "session", "native", "Task native")
                real.open(task)
                File(task.path).resolve("base.txt").writeText("agent\n")
                task = task.copy(resultCommit = real.capture(task), targetCommit = real.target(task))
                task = task.copy(mergeCommit = checkNotNull(real.integrate(task)))
                real.deliver(task)
                assertEquals("agent\n", source.resolve("base.txt").readText())
                assertEquals(task.mergeCommit, git(source, "rev-parse", "HEAD"))
            } finally { checks.close() }
        }
    }

    @Test fun nonGitDetachedAndUnbornProjectsAreUnavailable() = runTest { fixture {
        val empty = File(root, "empty").apply { mkdirs() }
        assertFalse(port.availability(project.copy(path = empty.path)).available)
        git(empty, "init", "-b", "main")
        assertFalse(port.availability(project.copy(path = empty.path)).available)
        git(source, "checkout", "--detach")
        assertFalse(port.availability(project).available)
    } }

    @Test fun sandboxProbeFailureDuringAvailabilityIsUnavailableNotAThrow() = runTest { fixture {
        val unavailable = object : CommandChecks by gitChecks {
            override suspend fun run(command: CheckCommand): CheckResult =
                if (command.policy == CheckPolicy.GIT_READ_ONLY) throw CheckOutcomeUnknown() else gitChecks.run(command)
        }
        assertFalse(port(checks = unavailable).availability(project).available)
    } }

    @Test fun concurrentDestinationChangesAreIntegratedWithoutLosingEitherSide() = runTest { fixture {
        val task = open()
        File(task.path).resolve("task.txt").writeText("task")
        source.resolve("user.txt").writeText("user")
        git(source, "add", "."); git(source, "commit", "-m", "parallel user")
        val target = git(source, "rev-parse", "HEAD")
        val result = prepare(task)
        assertEquals(listOf(result.mergeCommit, target), git(source, "rev-list", "--parents", "-n", "1", result.mergeCommit).split(" "),
            "перенос сохраняет линейную историю: коммит задачи стоит прямо на ветке назначения")
        port.deliver(result)
        assertEquals("user", source.resolve("user.txt").readText())
        assertEquals("task", source.resolve("task.txt").readText())
        assertEquals(result.mergeCommit, git(source, "rev-parse", "HEAD"))
    } }

    @Test fun repeatedDestinationAdvanceKeepsCapturedResultReachable() = runTest { fixture {
        var task = open()
        File(task.path).resolve("task.txt").writeText("task")
        task = task.copy(resultCommit = port.capture(task))

        source.resolve("first.txt").writeText("first")
        git(source, "add", "."); git(source, "commit", "-m", "first parallel task")
        task = task.copy(targetCommit = port.target(task))
        task = task.copy(mergeCommit = checkNotNull(port.integrate(task)))
        task = task.copy(integratedCommit = task.targetCommit)

        source.resolve("second.txt").writeText("second")
        git(source, "add", "."); git(source, "commit", "-m", "second parallel task")
        task = task.copy(targetCommit = port.target(task))
        task = task.copy(mergeCommit = checkNotNull(port.integrate(task)))

        port.verify(task, "test-verification-5")
        port.deliver(task)
        assertEquals("task", source.resolve("task.txt").readText())
        assertEquals("first", source.resolve("first.txt").readText())
        assertEquals("second", source.resolve("second.txt").readText())
    } }

    @Test fun rewrittenDestinationAfterIntegrationKeepsCapturedResultReachableDuringConflictRepair() = runTest { fixture {
        var task = open()
        File(task.path).resolve("base.txt").writeText("agent\n")
        task = task.copy(resultCommit = port.capture(task))

        source.resolve("first.txt").writeText("first")
        git(source, "add", "."); git(source, "commit", "-m", "first destination")
        val firstTarget = port.target(task)
        task = task.copy(targetCommit = firstTarget)
        task = task.copy(mergeCommit = checkNotNull(port.integrate(task)), integratedCommit = firstTarget)

        git(source, "reset", "--hard", task.baseCommit)
        source.resolve("base.txt").writeText("user\n")
        git(source, "commit", "-am", "rewritten destination")
        task = task.copy(targetCommit = port.target(task))
        assertNull(port.integrate(task))

        File(task.path).resolve("base.txt").writeText("user\nagent\n")
        git(File(task.path), "add", "base.txt")
        task = task.copy(phase = TaskWorktreePhase.CONFLICT)
        task = task.copy(mergeCommit = checkNotNull(port.integrate(task)))
        port.deliver(task)

        assertEquals("user\nagent\n", source.resolve("base.txt").readText())
    } }

    @Test fun fixAlreadyPresentUpstreamIsDroppedInsteadOfConflicting() = runTest { fixture {
        val task = open()
        File(task.path).resolve("base.txt").writeText("fixed\n")
        git(File(task.path), "commit", "-am", "agent fix")
        source.resolve("base.txt").writeText("fixed\n")
        git(source, "commit", "-am", "the same fix")
        val tip = git(source, "rev-parse", "HEAD")
        val result = prepare(task)
        assertEquals(tip, result.mergeCommit)
        port.deliver(result)
        assertEquals(tip, git(source, "rev-parse", "HEAD"))
        assertEquals("fixed\n", source.resolve("base.txt").readText())
    } }

    @Test fun preRunUpdateBringsCleanCopyOntoTheDestinationTip() = runTest { fixture {
        val task = open()
        File(task.path).resolve("task.txt").writeText("task")
        git(File(task.path), "add", "."); git(File(task.path), "commit", "-m", "agent")
        source.resolve("user.txt").writeText("user")
        git(source, "add", "."); git(source, "commit", "-m", "user")
        val tip = git(source, "rev-parse", "HEAD")
        val refreshed = port.refresh(task)
        assertTrue(refreshed.updated, refreshed.note)
        assertEquals(0, refreshed.behind)
        assertEquals(tip, refreshed.targetCommit)
        assertEquals(tip, git(File(task.path), "rev-parse", "HEAD^"))
        assertEquals("user", File(task.path).resolve("user.txt").readText())
        assertEquals("task", File(task.path).resolve("task.txt").readText())
        assertEquals(tip, git(source, "rev-parse", "HEAD"), "исходная папка не меняется до доставки")
        assertFalse(port.refresh(task.copy(integratedCommit = tip)).updated)
    } }

    @Test fun preRunUpdateDeclinesWithoutTouchingUnsavedWork() = runTest { fixture {
        val task = open()
        File(task.path).resolve("base.txt").writeText("agent\n")
        source.resolve("base.txt").writeText("user\n")
        git(source, "commit", "-am", "user")
        val declined = port.refresh(task)
        assertFalse(declined.updated)
        assertEquals(1, declined.behind)
        assertEquals(git(source, "rev-parse", "HEAD"), declined.targetCommit)
        assertNotNull(declined.note)
        assertEquals("agent\n", File(task.path).resolve("base.txt").readText())
        assertEquals(task.baseCommit, git(File(task.path), "rev-parse", "HEAD"))
    } }

    @Test fun preRunUpdateLeavesTheConflictOnTheWorkingBranch() = runTest { fixture {
        val task = open()
        File(task.path).resolve("base.txt").writeText("agent\n")
        git(File(task.path), "commit", "-am", "agent")
        val before = git(File(task.path), "rev-parse", "HEAD")
        source.resolve("base.txt").writeText("user\n")
        git(source, "commit", "-am", "user")
        val declined = port.refresh(task)
        assertTrue(declined.pendingTransfer, declined.note)
        assertEquals(1, declined.behind)
        assertEquals(git(source, "rev-parse", "HEAD"), declined.targetCommit)
        assertNotNull(declined.note)
        // Конфликт остаётся в рабочей копии на ветке задачи: перенос не откачен, исходная папка не менялась.
        val dir = File(task.path)
        assertEquals(before, git(dir, "rev-parse", "refs/heads/${task.branch}"))
        assertTrue(git(dir, "status", "--porcelain").isNotEmpty())
        assertContains(git(dir, "diff", "--name-only", "--diff-filter=U"), "base.txt")
        // Возобновление до разрешения: перенос всё ещё в копии, заметка доходит до агента без откката.
        val restarted = port.refresh(task)
        assertTrue(restarted.pendingTransfer)
        assertNotNull(restarted.note)
        // Агент возобновляемого прогона разрешает конфликт на рабочей ветке и завершает перенос.
        dir.resolve("base.txt").writeText("user\nagent\n")
        git(dir, "add", "base.txt")
        git(dir, "-c", "core.editor=true", "rebase", "--continue")
        assertEquals(declined.targetCommit, git(dir, "rev-parse", "HEAD^"), "ветка задачи перенесена на вершину ветки назначения")
        assertEquals(git(dir, "rev-parse", "HEAD"), git(dir, "rev-parse", "refs/heads/${task.branch}"))
        val settled = port.refresh(task.copy(integratedCommit = declined.targetCommit))
        assertFalse(settled.pendingTransfer)
        assertEquals(0, settled.behind)
        assertEquals("user\nagent\n", dir.resolve("base.txt").readText())
    } }

    @Test fun captureFinishesATransferTheAgentResolvedButLeftUncontinued() = runTest { fixture {
        val task = open()
        File(task.path).resolve("base.txt").writeText("agent\n")
        git(File(task.path), "commit", "-am", "agent")
        source.resolve("base.txt").writeText("user\n")
        git(source, "commit", "-am", "user")
        val declined = port.refresh(task)
        assertTrue(declined.pendingTransfer)
        // Агент разрешил конфликт в индексе, но не продолжил перенос: приёмка доводит его на рабочей ветке.
        val dir = File(task.path)
        dir.resolve("base.txt").writeText("user\nagent\n")
        git(dir, "add", "base.txt")
        val record = task.copy(integratedCommit = declined.targetCommit)
        val captured = port.capture(record)
        assertEquals(git(dir, "rev-parse", "refs/heads/${task.branch}"), captured)
        assertEquals(declined.targetCommit, git(dir, "rev-parse", "HEAD^"))
        assertEquals("user\nagent\n", dir.resolve("base.txt").readText())
        val merged = record.copy(resultCommit = captured, targetCommit = port.target(record))
            .let { it.copy(mergeCommit = checkNotNull(port.integrate(it))) }
        port.verify(merged, "test-verification-6")
        port.deliver(merged)
        assertEquals("user\nagent\n", source.resolve("base.txt").readText())
    } }

    @Test fun unresolvedTransferConflictKeepsCaptureRecoverable() = runTest { fixture {
        val task = open()
        File(task.path).resolve("base.txt").writeText("agent\n")
        git(File(task.path), "commit", "-am", "agent")
        source.resolve("base.txt").writeText("user\n")
        git(source, "commit", "-am", "user")
        assertTrue(port.refresh(task).pendingTransfer)
        val failure = assertFailsWith<IllegalArgumentException> { port.capture(task) }
        assertEquals("Сначала разрешите конфликт объединения", failure.message)
        // Разрешение на рабочей ветке делает следующий захват успешным.
        File(task.path).resolve("base.txt").writeText("user\nagent\n")
        git(File(task.path), "add", "base.txt")
        assertTrue(port.capture(task).isNotBlank())
    } }

    @Test fun targetAdvanceWithoutTaskChangesDoesNotCreateMergeCommit() = runTest { fixture {
        val task = open()
        source.resolve("base.txt").appendText("next\n")
        git(source, "commit", "-am", "next")
        val target = git(source, "rev-parse", "HEAD")
        port.deliver(prepare(task))
        assertEquals(target, git(source, "rev-parse", "HEAD"))
    } }

    @Test fun conflictStaysInTaskCopyUntilRepair() = runTest { fixture {
        var task = open()
        File(task.path).resolve("base.txt").writeText("agent\n")
        task = task.copy(resultCommit = port.capture(task))
        source.resolve("base.txt").writeText("user\n")
        git(source, "commit", "-am", "user")
        task = task.copy(targetCommit = port.target(task))
        assertNull(port.integrate(task))
        assertEquals("user\n", source.resolve("base.txt").readText())
        // Сохранённый результат остаётся достижимым, а прерванный перенос не теряет ветку задачи.
        assertEquals(task.resultCommit, git(File(task.path), "rev-parse", "refs/heads/${task.branch}"))
        assertNull(port.integrate(task))
        File(task.path).resolve("base.txt").writeText("user\nagent\n")
        git(File(task.path), "add", "base.txt")
        task = task.copy(phase = TaskWorktreePhase.CONFLICT)
        task = task.copy(mergeCommit = checkNotNull(port.integrate(task)))
        port.deliver(task)
        assertEquals("user\nagent\n", source.resolve("base.txt").readText())
    } }

    @Test fun legacyConflictWithoutPreIntegrationRefRecoversFromTaskBranch() = runTest { fixture {
        var task = open()
        File(task.path).resolve("base.txt").writeText("agent\n")
        task = task.copy(resultCommit = port.capture(task))
        source.resolve("base.txt").writeText("user\n")
        git(source, "commit", "-am", "user")
        task = task.copy(targetCommit = port.target(task))
        assertNull(port.integrate(task))

        val commonPath = git(File(task.path), "rev-parse", "--git-common-dir")
        val common = File(commonPath).let { if (it.isAbsolute) it else File(task.path, commonPath) }
        val preRefs = common.resolve("refs/magicpaper").listFiles().orEmpty()
            .filter { it.name.startsWith("task-pre-integration-") }
        assertTrue(preRefs.isNotEmpty())
        preRefs.forEach { it.delete() }

        File(task.path).resolve("base.txt").writeText("user\nagent\n")
        git(File(task.path), "add", "base.txt")
        task = task.copy(phase = TaskWorktreePhase.CONFLICT)
        task = task.copy(mergeCommit = checkNotNull(port.integrate(task)))
        port.deliver(task)
        assertEquals("user\nagent\n", source.resolve("base.txt").readText())
    } }

    @Test fun dirtyOrSwitchedDestinationCannotBeOverwritten() = runTest { fixture {
        val task = open()
        File(task.path).resolve("task").writeText("result")
        val result = prepare(task)
        source.resolve("user").writeText("keep")
        assertFailsWith<IllegalArgumentException> { port.deliver(result) }
        source.resolve("user").delete()
        git(source, "switch", "-c", "another")
        assertFailsWith<IllegalArgumentException> { port.deliver(result) }
        assertFalse(source.resolve("task").exists())
    } }

    @Test fun nextTaskReusesDirectoryButNotBranchAndRejectsDirtyPool() = runTest { fixture {
        val first = prepare(open())
        port.deliver(first)
        val next = port.describe(project, "session", "two", "Task two").copy(reuseBranch = first.branch, reuseCommit = first.mergeCommit)
        assertEquals(first.path, next.path)
        File(first.path).resolve("unexpected").writeText("preserve")
        assertFailsWith<IllegalArgumentException> { port.open(next, first) }
        File(first.path).resolve("unexpected").delete()
        port.open(next, first)
        assertNotEquals(first.branch, next.branch)
        assertEquals(next.branch, git(File(next.path), "branch", "--show-current"))
        port.open(next) // replay of the saved preparation intent
    } }

    @Test fun taskBranchAndCommitCarryMeaningfulNameOfTheRequest() = runTest { fixture {
        val record = port.describe(project, "session", "one", "Фикс авторизации: убрать юникод из имён!")
        assertEquals("magicpaper/worktree-fiks-avtorizacii-ubrat-yunikod-iz-imen", record.branch)
        port.open(record)
        File(record.path).resolve("done.txt").writeText("yes")
        port.capture(record)
        val dir = File(record.path)
        val subject = git(dir, "log", "-1", "--pretty=%s")
        val body = git(dir, "log", "-1", "--pretty=%b")
        assertEquals("Фикс авторизации: убрать юникод из имён!", subject)
        assertTrue(body.contains("one"), body)
    } }

    @Test fun occupiedBranchNameGetsUniqueSuffixInsteadOfFailing() = runTest { fixture {
        val label = "Одна и та же задача"
        git(source, "branch", "magicpaper/worktree-odna-i-ta-zhe-zadacha")
        val taken = port.describe(project, "session", "one", label)
        assertTrue(taken.branch.startsWith("magicpaper/worktree-odna-i-ta-zhe-zadacha-"), taken.branch)
        git(source, "branch", "-D", "magicpaper/worktree-odna-i-ta-zhe-zadacha")
        val free = port.describe(project, "session", "two", label)
        assertEquals("magicpaper/worktree-odna-i-ta-zhe-zadacha", free.branch)
    } }

    @Test fun crashAfterDeliveryIsRecognizedWithoutReapplying() = runTest { fixture {
        val task = open()
        File(task.path).resolve("result").writeText("once")
        val result = prepare(task)
        val interrupted = port(checkpoint = { if (it == "delivered") error("crash") })
        assertFailsWith<IllegalStateException> { interrupted.deliver(result) }
        val reopened = port()
        assertTrue(reopened.delivered(result))
        val head = git(source, "rev-parse", "HEAD")
        reopened.deliver(result)
        assertEquals(head, git(source, "rev-parse", "HEAD"))
    } }

    @Test fun destinationAdvanceHasExplicitOutcomeAndDoesNotOverwriteNewCommit() = runTest { fixture {
        val task = open()
        File(task.path).resolve("result").writeText("task")
        val result = prepare(task)
        source.resolve("user").writeText("later")
        git(source, "add", "."); git(source, "commit", "-m", "later")
        val head = git(source, "rev-parse", "HEAD")
        assertFailsWith<TaskDestinationChanged> { port.deliver(result) }
        assertEquals(head, git(source, "rev-parse", "HEAD"))
        assertFalse(source.resolve("result").exists())
    } }

    @Test fun failedLeaseReleaseCanBeReconciledBeforeRetryingDelivery() = runTest { fixture {
        val kv = InMemoryKeyValueStore()
        val repo = JsonCodingProjectRepository(kv, Json { encodeDefaults = true })
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        var armed = false
        leaseCheckpoint = { event ->
            if (armed && event == "project-lock-releasing") { armed = false; error("injected release failure") }
        }
        val leases = GitPlanningWorkspace(File(root, "leases"), authority)
        val service = worktreeService(repo, port, leases)
        val task = service.begin(project, "session", "request")
        File(task.path).resolve("result").writeText("once")
        service.handoff(ToolExecutionContext(project.id, "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.CODE), true, emptyList())
        armed = true
        assertFailsWith<IllegalStateException> { service.complete(project, "session", "request", repair = {}) }
        assertEquals(TaskWorktreePhase.COMPLETE, service.complete(project, "session", "request", repair = {}).phase)
        assertEquals("once", source.resolve("result").readText())
    } }

    @Test fun durableTaskReopensAcrossGenerationAndRequiresHandoff() = runTest { fixture {
        val kv = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        var repo = JsonCodingProjectRepository(kv, json)
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1, runtimeGeneration = 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        var service = worktreeService(repo, port, GitPlanningWorkspace(File(root, "leases"), authority))
        val first = service.begin(project, "session", "request")
        File(first.path).resolve("result").writeText("kept")
        assertFailsWith<IllegalStateException> { service.complete(project, "session", "request", repair = { error("unexpected") }) }
        repo = JsonCodingProjectRepository(kv, json)
        repo.updateSession(project.id, "session") { it.copy(runtimeGeneration = 2) }
        service = worktreeService(repo, port(), GitPlanningWorkspace(File(root, "leases"), authority))
        val resumed = service.begin(project, "session", "request")
        assertEquals(first.path, resumed.path)
        assertEquals(first.branch, resumed.branch)
        val ctx = ToolExecutionContext(project.id, "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.CODE, runtimeGeneration = 2)
        assertFailsWith<IllegalArgumentException> { service.handoff(ctx.copy(runtimeGeneration = 1), true, emptyList()) }
        service.handoff(ctx, true, emptyList())
        val finished = service.complete(project, "session", "request", repair = { error("unexpected") })
        assertEquals(TaskWorktreePhase.COMPLETE, finished.phase)
        assertEquals("kept", source.resolve("result").readText())
    } }

    @Test fun restartWhileRunningKeepsTheWorktreeAndActualizesItsBranchOnResume() = runTest { fixture {
        val kv = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        var repo = JsonCodingProjectRepository(kv, json)
        repo.save(project)
        repo.saveSession(CodingSession("session", project.id, "Task", 1,
            pendingRun = CodingRunCheckpoint("request", "do", worktreeEnabled = true)))
        var service = worktreeService(repo, port, GitPlanningWorkspace(File(root, "leases"), authority))
        val first = service.begin(project, "session", "request")
        val dir = File(first.path)
        dir.resolve("agent.txt").writeText("work in progress")
        git(dir, "add", "."); git(dir, "commit", "-m", "agent progress")
        // Перезапуск приложения: новое хранилище, новый сервис и новый порт читают сохранённую запись задачи.
        repo = JsonCodingProjectRepository(kv, json)
        service = worktreeService(repo, port(), GitPlanningWorkspace(File(root, "leases"), authority))
        source.resolve("user.txt").writeText("user")
        git(source, "add", "."); git(source, "commit", "-m", "parallel user")
        val tip = git(source, "rev-parse", "HEAD")
        val resumed = service.begin(project, "session", "request")
        assertEquals(first.path, resumed.path)
        assertEquals(first.branch, resumed.branch)
        val record = repo.sessions(project.id).single().taskWorktree!!
        assertEquals(tip, record.integratedCommit, "возобновлённая копия перенесена на вершину ветки назначения")
        assertEquals(0, record.behindCommits)
        assertNull(record.refreshNote)
        assertFalse(record.pendingTransfer)
        assertEquals(tip, git(dir, "rev-parse", "HEAD^"), "коммиты задачи переписаны на вершину ветки назначения")
        assertEquals("user", dir.resolve("user.txt").readText())
        dir.resolve("agent.txt").writeText("finished")
        service.handoff(ToolExecutionContext(project.id, "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.CODE), true, emptyList())
        val finished = service.complete(project, "session", "request", repair = { error("unexpected repair") })
        assertEquals(TaskWorktreePhase.COMPLETE, finished.phase)
        assertEquals("finished", source.resolve("agent.txt").readText())
    } }

    companion object {
        private fun git(dir: File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@localhost", "-c", "commit.gpgSign=false") + args)
                .directory(dir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output.trim()
        }
    }
}
