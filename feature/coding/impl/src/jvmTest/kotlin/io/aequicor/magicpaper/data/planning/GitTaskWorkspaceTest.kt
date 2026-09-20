package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolRole
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class GitTaskWorkspaceTest {
    private class Fixture {
        val root = Files.createTempDirectory("magicpaper-task-test-").toFile()
        val source = File(root, "source").apply { mkdirs() }
        val pool = File(root, "pool")
        val project = CodingProject("p", "Project", source.path, 1)
        val port = GitTaskWorkspace(pool)
        init {
            git(source, "init", "-b", "main")
            // Delivery checks out files with the real Git; the developer's global core.autocrlf must not decide their bytes.
            git(source, "config", "core.autocrlf", "false")
            source.resolve("base.txt").writeText("base\n")
            git(source, "add", "."); git(source, "commit", "-m", "base")
        }
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
        val service = TaskWorktreeService(repo, port, GitPlanningWorkspace(File(root, "leases")))
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
        val service = TaskWorktreeService(repo, port, GitPlanningWorkspace(File(root, "leases")))
        val task = service.begin(project, "session", "request")
        File(task.path).resolve("result.txt").writeText("incomplete")
        service.handoff(ToolExecutionContext(project.id, "session", "session", "request", ToolRole.CHAT, CodingInteractionMode.CODE), false, emptyList())
        val blocked = repo.sessions(project.id).single().taskWorktree!!
        val failure = assertFailsWith<IllegalStateException> { service.complete(project, "session", "request", repair = {}) }
        assertEquals(blocked.error, failure.message)
        assertFalse(source.resolve("result.txt").exists())
        assertEquals(TaskWorktreePhase.RUNNING, blocked.phase)
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
        port.verify(result)
        port.deliver(result)
        assertEquals("pending", source.resolve("remaining.txt").readText())
        assertEquals(agent, git(source, "rev-parse", "HEAD^"))
        assertEquals("", git(source, "status", "--porcelain"))
    } }

    @Test fun verifyRunsTaskChecksInManagedCopyAndReportsTheirOutput() = runTest { fixture {
        val checked = GitTaskWorkspace(pool, checks = TaskWorktreeIntegrationChecks())
        val task = open()
        val dir = File(task.path)
        dir.resolve("result.txt").writeText("ok")
        git(dir, "add", "."); git(dir, "commit", "-m", "result")
        val record = prepare(task)
        checked.verify(record.copy(checks = listOf(listOf("git", "--version"))))
        val failure = assertFailsWith<IllegalStateException> {
            checked.verify(record.copy(checks = listOf(listOf("git", "rev-parse", "--verify", "refs/heads/missing-branch"))))
        }
        assertTrue(failure.message!!.startsWith("Проверка результата завершилась с ошибкой"), failure.message)
        assertTrue(failure.message!!.contains("fatal"), failure.message)
        assertEquals(record.mergeCommit, git(dir, "rev-parse", "HEAD"))
        assertEquals("", git(dir, "status", "--porcelain", "--untracked-files=all"))
    } }

    @Test fun failedTestIdentitySurvivesWarningsFromParallelBuildTasks() = runTest { fixture {
        val output = "PaperActivityIndicatorTest > pulseIsSharedByBothSilhouettes FAILED\n" +
            "w: unrelated compilation warning\n".repeat(300) + "BUILD FAILED in 16s"
        val runner = object : SessionIntegrationCheckRunner {
            override suspend fun run(path: String, id: String, command: List<String>) =
                SessionIntegrationCheck(command, 1, output, null)
            override fun abort(id: String) = Unit
            override suspend fun reconcile(id: String) = Unit
        }
        val checked = GitTaskWorkspace(pool, checks = runner)
        val task = open()
        File(task.path, "result.txt").writeText("pending result")
        val record = prepare(task)
        val failure = assertFailsWith<IllegalStateException> {
            checked.verify(record.copy(checks = listOf(listOf("./gradlew", "test"))))
        }
        assertContains(failure.message.orEmpty(), "PaperActivityIndicatorTest > pulseIsSharedByBothSilhouettes FAILED")
        assertContains(failure.message.orEmpty(), "BUILD FAILED in 16s")
        assertTrue(failure.message.orEmpty().length < 2200, "Diagnostic remains bounded")
        assertEquals(record.baseCommit, git(source, "rev-parse", "HEAD"), "A failed check never delivers the task")
    } }

    @Test fun dirtyIndexAndUntrackedFilesBlockPreparation() = runTest { fixture {
        source.resolve("untracked").writeText("user")
        assertFailsWith<IllegalArgumentException> { port.describe(project, "s", "r", "label") }
        git(source, "add", "untracked")
        assertFailsWith<IllegalArgumentException> { port.describe(project, "s", "r", "label") }
        assertEquals("user", source.resolve("untracked").readText())
    } }

    @Test fun nonGitDetachedAndUnbornProjectsAreUnavailable() = runTest { fixture {
        val empty = File(root, "empty").apply { mkdirs() }
        assertFalse(port.availability(project.copy(path = empty.path)).available)
        git(empty, "init", "-b", "main")
        assertFalse(port.availability(project.copy(path = empty.path)).available)
        git(source, "checkout", "--detach")
        assertFalse(port.availability(project).available)
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

        port.verify(task)
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
        port.verify(merged)
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
        val interrupted = GitTaskWorkspace(pool, checkpoint = { if (it == "delivered") error("crash") })
        assertFailsWith<IllegalStateException> { interrupted.deliver(result) }
        val reopened = GitTaskWorkspace(pool)
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
        val leases = GitPlanningWorkspace(File(root, "leases")) { event ->
            if (armed && event == "project-lock-releasing") { armed = false; error("injected release failure") }
        }
        val service = TaskWorktreeService(repo, port, leases)
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
        var service = TaskWorktreeService(repo, port, GitPlanningWorkspace(File(root, "leases")))
        val first = service.begin(project, "session", "request")
        File(first.path).resolve("result").writeText("kept")
        assertFailsWith<IllegalStateException> { service.complete(project, "session", "request", repair = { error("unexpected") }) }
        repo = JsonCodingProjectRepository(kv, json)
        repo.updateSession(project.id, "session") { it.copy(runtimeGeneration = 2) }
        service = TaskWorktreeService(repo, GitTaskWorkspace(pool), GitPlanningWorkspace(File(root, "leases")))
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
        var service = TaskWorktreeService(repo, port, GitPlanningWorkspace(File(root, "leases")))
        val first = service.begin(project, "session", "request")
        val dir = File(first.path)
        dir.resolve("agent.txt").writeText("work in progress")
        git(dir, "add", "."); git(dir, "commit", "-m", "agent progress")
        // Перезапуск приложения: новое хранилище, новый сервис и новый порт читают сохранённую запись задачи.
        repo = JsonCodingProjectRepository(kv, json)
        service = TaskWorktreeService(repo, GitTaskWorkspace(pool), GitPlanningWorkspace(File(root, "leases")))
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
