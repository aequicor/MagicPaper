package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.deleteTree
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import java.io.File
import io.aequicor.magicpaper.domain.checks.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Branch delivery is separate from the planner's legacy file transfer. All writes stay in managed copies until ff-only delivery.
 * Объединение с веткой назначения выполняется переносом (rebase): история ветки остаётся линейной,
 * а каждое прерывание либо завершается, либо откатывается к сохранённой точке до переноса.
 */
class GitTaskWorkspace(
    private val authority: GitWorkspaceAuthority,
    private val root: File = File(System.getProperty("user.home"), ".MagicPaper/task-worktrees"),
    private val checkpoint: (String) -> Unit = {},
) : TaskWorkspace {
    private class Commands(val value: OwnedGitCommands) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Commands>
    }
    private suspend fun commands() = checkNotNull(currentCoroutineContext()[Commands]).value
    private suspend fun <T> reading(path: String, action: suspend () -> T): T =
        if (currentCoroutineContext()[Commands] != null) action()
        else withContext(Dispatchers.IO + Commands(OwnedGitCommands.readOnly(authority.checks, path))) { action() }

    private suspend fun <T> owned(record: TaskWorktree, operation: TaskWorkspaceOperation,
        kind: TaskWorktreeMachine.Operation, action: suspend () -> T): T {
        val pending = operation.pending
        require(operation.owner.projectId.isNotBlank() && operation.owner.sessionId.isNotBlank() &&
            pending.id.isNotBlank() && pending.taskId == record.taskId && pending.kind == kind && pending.generation >= 0) {
            "Операция принадлежит другой задаче"
        }
        val source = File(record.sourcePath).canonicalFile
        val destination = File(record.path).canonicalFile
        require(destination == File(root, hash(source.path + ":" + operation.owner.sessionId)).canonicalFile) {
            "Рабочая копия принадлежит другой сессии"
        }
        if (kind != TaskWorktreeMachine.Operation.OPEN) {
            val before = checkNotNull(pending.before)
            require(before.taskId == record.taskId && before.path == record.path && before.sourcePath == record.sourcePath &&
                before.branch == record.branch && before.targetBranch == record.targetBranch && before.baseCommit == record.baseCommit) {
                "Сохранённая операция принадлежит другой рабочей копии"
            }
        }
        val execution = requireNotNull(operation.leases.execution) { "Не передан захват рабочей копии задачи" }
        require(execution.canonicalPath == destination.path) { "Захват принадлежит другой рабочей копии" }
        val held = if (kind in setOf(TaskWorktreeMachine.Operation.OPEN, TaskWorktreeMachine.Operation.DELIVER)) {
            val original = requireNotNull(operation.leases.source) { "Не передан захват исходной папки" }
            require(original.canonicalPath == source.path) { "Захват принадлежит другому проекту" }
            listOf(original, execution)
        } else listOf(execution)
        val affected = setOf(source.path, destination.path) + gitMetadataResources(source) + gitMetadataResources(destination)
        return authority.owned(held, CheckScope(operation.owner.projectId, operation.owner.sessionId,
            pending.id, pending.generation), "task-${pending.kind.name.lowercase()}", affected) { transport ->
            withContext(Commands(transport)) { action() }
        }
    }

    override suspend fun availability(project: CodingProject): WorktreeAvailability = reading(project.path) {
        val source = File(project.path)
        if (!source.isDirectory) return@reading WorktreeAvailability(false, "Папка проекта недоступна")
        try {
            if (probe(source, "--version").first != 0)
                return@reading WorktreeAvailability(false, "Git недоступен")
            if (probe(source, "rev-parse", "--is-inside-work-tree").second.trim() != "true")
                return@reading WorktreeAvailability(false, "В папке нет Git-репозитория")
            if (probe(source, "symbolic-ref", "--quiet", "--short", "HEAD").first != 0)
                return@reading WorktreeAvailability(false, "Выберите Git-ветку")
            if (probe(source, "rev-parse", "--verify", "HEAD").first != 0)
                return@reading WorktreeAvailability(false, "В ветке ещё нет коммитов")
        } catch (unknown: CheckOutcomeUnknown) {
            // A sandbox probe failure marks checks unavailable for this visit; restoring the
            // session list must survive it, not just the check that first discovers it.
            return@reading WorktreeAvailability(false, "Проверка Git временно недоступна")
        }
        WorktreeAvailability(true)
    }

    override suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String): TaskWorktree = reading(project.path) {
        val source = File(git(File(project.path), "rev-parse", "--show-toplevel").trim()).canonicalFile
        clean(source, SOURCE_FOLDER)
        val target = git(source, "symbolic-ref", "--short", "HEAD").trim()
        val base = git(source, "rev-parse", "HEAD").trim()
        val slot = File(root, hash(source.path + ":" + sessionId)).canonicalFile
        TaskWorktree(taskId, source.path, target, base, slot.path, taskBranch(source, label, hash(sessionId + ":" + taskId)),
            label = label)
    }

    override suspend fun open(record: TaskWorktree, operation: TaskWorkspaceOperation, previous: TaskWorktree?) = owned(record, operation, TaskWorktreeMachine.Operation.OPEN) {
        val source = File(record.sourcePath)
        val dir = managed(record, mustExist = false)
        // Description is read-only and can precede acquisition of the canonical root lease.
        // Reject a changed source before creating/switching any managed worktree or branch.
        require(git(source, "symbolic-ref", "--short", "HEAD").trim() == record.targetBranch &&
            head(source) == record.baseCommit) { "Исходная ветка изменилась после подготовки задачи" }
        clean(source, SOURCE_FOLDER)
        if (dir.exists()) {
            val branch = git(dir, "symbolic-ref", "--short", "HEAD").trim()
            if (branch == record.branch) {
                reconcile(record)
                require(git(dir, "rev-parse", "HEAD").trim() == record.baseCommit) { "Подготовленная ветка задачи изменилась" }
                clean(dir, TASK_COPY)
                return@owned
            }
            require(record.reuseBranch.isNotBlank() && branch == record.reuseBranch) { "Рабочая копия принадлежит другой задаче" }
            clean(dir, TASK_COPY)
            require(git(dir, "rev-parse", "HEAD").trim() == record.reuseCommit) { "Сохранённая рабочая копия изменена" }
            git(dir, "switch", "-c", record.branch, record.baseCommit)
        } else {
            root.mkdirs()
            val branchExists = probe(source, "show-ref", "--verify", "--quiet", "refs/heads/${record.branch}").first == 0
            if (branchExists) {
                require(git(source, "rev-parse", "refs/heads/${record.branch}").trim() == record.baseCommit) { "Ветка задачи изменилась" }
                git(source, "worktree", "add", dir.path, record.branch)
            } else git(source, "worktree", "add", "-b", record.branch, dir.path, record.baseCommit)
        }
        checkpoint("opened")
    }

    override suspend fun reconcile(record: TaskWorktree) = reading(record.sourcePath) {
        val dir = managed(record)
        // Во время переноса HEAD отделён: ветка задачи остаётся на точке до переноса, поэтому проверяется она.
        val rebasing = rebaseInProgress(dir)
        val own = if (rebasing) "refs/heads/${record.branch}" else "HEAD"
        if (rebasing) require(probe(dir, "show-ref", "--verify", "--quiet", own).first == 0) { "Ветка рабочей копии изменена" }
        else require(git(dir, "symbolic-ref", "--short", "HEAD").trim() == record.branch) { "Ветка рабочей копии изменена" }
        require(File(git(dir, "rev-parse", "--path-format=absolute", "--git-common-dir").trim()).canonicalFile ==
            File(git(File(record.sourcePath), "rev-parse", "--path-format=absolute", "--git-common-dir").trim()).canonicalFile) { "Рабочая копия принадлежит другому репозиторию" }
        // Перенос на ветку назначения переписывает коммиты задачи: историю продолжает исходная база либо сохранённая точка объединения.
        require(ancestor(dir, record.baseCommit, own) ||
            (record.integratedCommit.isNotBlank() && ancestor(dir, record.integratedCommit, own))) { "История задачи больше не продолжает исходный коммит" }
    }

    override suspend fun capture(record: TaskWorktree, operation: TaskWorkspaceOperation): String = owned(record, operation, TaskWorktreeMachine.Operation.CAPTURE) {
        reconcile(record)
        val dir = managed(record)
        if (rebaseInProgress(dir)) {
            // Разрешённый агентом перенос доводится до конца здесь: решение конфликта уже стоит на рабочей ветке.
            require(unmerged(dir).isBlank()) { "Сначала разрешите конфликт объединения" }
            continueRebase(dir)
        }
        require(!mergeInProgress(dir)) { "Сначала разрешите конфликт объединения" }
        if (git(dir, "status", "--porcelain").isNotBlank()) {
            git(dir, "add", "-A")
            git(dir, "commit", "-m", commitSubject(record), "-m", "MagicPaper task: ${record.taskId}")
        }
        checkpoint("captured")
        git(dir, "rev-parse", "HEAD").trim()
    }

    override suspend fun target(record: TaskWorktree): String = reading(record.sourcePath) {
        val source = File(record.sourcePath)
        require(git(source, "symbolic-ref", "--short", "HEAD").trim() == record.targetBranch) { "Верните исходную ветку ${record.targetBranch} и повторите слияние" }
        clean(source, SOURCE_FOLDER)
        git(source, "rev-parse", "HEAD").trim()
    }

    override suspend fun refresh(record: TaskWorktree, operation: TaskWorkspaceOperation): TaskWorktreeRefresh = owned(record, operation, TaskWorktreeMachine.Operation.REFRESH) {
        reconcile(record)
        val dir = managed(record)
        val target = "refs/heads/${record.targetBranch}"
        if (probe(dir, "rev-parse", "--verify", "--quiet", target).first != 0)
            return@owned TaskWorktreeRefresh(note = "Ветка назначения ${record.targetBranch} недоступна")
        val tip = git(dir, "rev-parse", target).trim()
        // Незавершённый перенос остаётся на рабочей ветке: его конфликт разрешает возобновляемый агент в самой копии.
        // Подписывается фактическая точка переноса: ветка назначения могла уйти и дальше неё.
        if (rebaseInProgress(dir))
            return@owned TaskWorktreeRefresh(targetCommit = rebaseOntoCommit(dir, tip), pendingTransfer = true,
                note = "Рабочая копия посреди переноса на ветку назначения ${record.targetBranch}: разреши конфликт в файлах " +
                    "(сохрани обе стороны), добавь их в индекс (git add) и продолжи перенос (git rebase --continue), " +
                    "затем продолжай задачу")
        if (mergeInProgress(dir))
            return@owned TaskWorktreeRefresh(targetCommit = tip, note = "Сначала завершите объединение с веткой назначения")
        val behind = distance(dir, tip)
        if (behind == 0) return@owned TaskWorktreeRefresh(behind, tip)
        // Правки агента не принадлежат приложению: копия с несохранёнными изменениями обновится только при слиянии.
        if (git(dir, "status", "--porcelain").isNotBlank())
            return@owned TaskWorktreeRefresh(behind, tip, note = "В копии есть несохранённые изменения")
        // До запуска копию ведёт агент возобновляемого прогона: конфликт переноса остаётся на рабочей ветке,
        // а откатывается только сбой без конфликтных файлов.
        val before = head(dir)
        val (code, output) = if (ancestor(dir, "HEAD", tip)) probe(dir, "merge", "--ff-only", tip) else rebaseOnto(dir, tip)
        if (code != 0 || !ancestor(dir, tip, "HEAD")) {
            if (rebaseInProgress(dir)) {
                if (unmerged(dir).isNotBlank()) {
                    checkpoint("refresh-pending")
                    AppLog.info("coding.worktree", "task.refresh.conflict", mapOf("entityId" to record.taskId, "result" to behind.toString()))
                    return@owned TaskWorktreeRefresh(behind, tip, pendingTransfer = true,
                        note = "Перенос остановлен конфликтом: разреши его в файлах рабочей копии (сохрани обе стороны), " +
                            "добавь их в индекс (git add) и продолжи перенос (git rebase --continue), затем продолжай задачу")
                }
                // Сбой без конфликтных файлов не содержит решения, которое можно сохранить: откат.
                git(dir, "rebase", "--abort")
            }
            check(head(dir) == before) { "Не удалось откатить обновление рабочей копии\n${tail(output)}" }
            AppLog.debug("coding.worktree", "task.refresh.declined", mapOf("entityId" to record.taskId, "result" to code.toString()))
            return@owned TaskWorktreeRefresh(behind, tip,
                note = "Изменения ветки назначения не удалось применить до запуска; объединение выполнится при слиянии")
        }
        checkpoint("refreshed")
        AppLog.info("coding.worktree", "task.refreshed", mapOf("entityId" to record.taskId, "result" to behind.toString()))
        TaskWorktreeRefresh(distance(dir, tip), tip, updated = true)
    }

    override suspend fun integrate(record: TaskWorktree, operation: TaskWorkspaceOperation): String? = owned(record, operation, TaskWorktreeMachine.Operation.INTEGRATE) {
        reconcile(record)
        val dir = managed(record)
        requireResult(dir, record)
        if (mergeInProgress(dir)) {
            // Слияние, оставленное предыдущей версией доставки, завершается, а не отбрасывается.
            if (unmerged(dir).isNotBlank()) return@owned null
            git(dir, "add", "-A")
            git(dir, "commit", "--no-edit")
        } else {
            if (rebaseInProgress(dir)) {
                // Прерванный перенос без сохранённого CONFLICT не содержит разрешения конфликта: откат и повтор.
                if (record.phase != TaskWorktreePhase.CONFLICT) git(dir, "rebase", "--abort")
                else if (!continueRebase(dir)) return@owned null
            }
            if (!ancestor(dir, record.targetCommit, "HEAD")) {
                clean(dir, TASK_COPY)
                if (ancestor(dir, "HEAD", record.targetCommit)) git(dir, "merge", "--ff-only", record.targetCommit)
                else {
                    // Первая точка до переноса сохраняется в общем репозитории: она доказывает, что исходный
                    // результат переписан нами, а не потерян. Повторный перенос после продвижения назначения
                    // не должен заменять её уже переписанным HEAD и тем самым уничтожать это доказательство.
                    preservePreIntegrationRef(dir, record)
                    val (code, output) = rebaseOnto(dir, record.targetCommit)
                    if (code != 0) {
                        if (unmerged(dir).isNotBlank()) return@owned null
                        if (!rebaseInProgress(dir) || !continueRebase(dir)) {
                            if (rebaseInProgress(dir)) git(dir, "rebase", "--abort")
                            error("Не удалось перенести задачу на ветку назначения\n${tail(output)}")
                        }
                    }
                }
            }
        }
        checkpoint("merged")
        head(dir)
    }

    override suspend fun verify(record: TaskWorktree, operation: TaskWorkspaceOperation) = owned(record, operation, TaskWorktreeMachine.Operation.VERIFY) {
        val dir = managed(record)
        val before = verificationSnapshot(dir.path, commands())
        AppLog.info("coding.worktree", "verification.started", mapOf("entityId" to record.taskId, "count" to record.checks.size.toString()))
        for ((index, args) in record.checks.withIndex()) {
            require(args.isNotEmpty() && args.none { '\u0000' in it }) { "Некорректная команда проверки" }
            val started = System.nanoTime()
            val result = commands().check(dir, args)
            val code = result.exitCode
            val status = if (result.blockedReason != null) "blocked" else if (code == 0) "passed" else "failed"
            // Ключи вне allowlist AppLog санитируются до `[redacted]`: программу называет имя файла, аргументы и вывод — только TRACE.
            AppLog.info("coding.worktree", "check.finished", mapOf("entityId" to record.taskId, "index" to index.toString(),
                "result" to code.toString(), "status" to status, "executable" to programName(args.first()),
                "argumentCount" to (args.size - 1).toString(), "durationMs" to ((System.nanoTime() - started) / 1_000_000).toString(),
                "bytes" to result.output.toByteArray(Charsets.UTF_8).size.toString()))
            if (status != "passed") AppLog.trace("coding.worktree", "check.output", mapOf("entityId" to record.taskId, "index" to index.toString())) {
                buildString {
                    append("command: ").append(checkCommandLine(args).take(TRACE_COMMAND))
                    result.blockedReason?.let { append("\nblocked: ").append(it.take(TRACE_COMMAND)) }
                    append("\noutput:\n").append(checkFailureDetail(result.output, TRACE_OUTPUT))
                }
            }
            if (code != 0 || result.blockedReason != null) {
                // Голый вердикт без причины вынуждает агента и пользователя угадывать; ограниченный хвост вывода уже санирован.
                val detail = checkFailureDetail(result.output)
                throw TaskWorktreeVerificationFailed(buildString {
                    append("Проверка результата завершилась с ошибкой. Исправьте изменения и повторите продолжение")
                    result.blockedReason?.let { append('\n').append(PlanningDiagnostics.redact(it)) }
                    if (detail.isNotEmpty()) append('\n').append(detail)
                })
            }
        }
        if (verificationSnapshot(dir.path, commands()) != before)
            throw TaskWorktreeVerificationFailed("Проверка изменила файлы задачи; нужна повторная приёмка")
        clean(dir, TASK_COPY)
    }

    override suspend fun delivered(record: TaskWorktree): Boolean = reading(record.sourcePath) {
        if (record.mergeCommit.isBlank() || !ancestor(File(record.sourcePath), record.mergeCommit, "refs/heads/${record.targetBranch}")) return@reading false
        // Ref ancestry alone cannot prove that an interrupted checkout finished updating its files/index.
        target(record)
        true
    }

    override suspend fun deliver(record: TaskWorktree, operation: TaskWorkspaceOperation) = owned(record, operation, TaskWorktreeMachine.Operation.DELIVER) {
        if (delivered(record)) return@owned
        if (target(record) != record.targetCommit) throw TaskDestinationChanged()
        val dir = managed(record)
        require(git(dir, "rev-parse", "HEAD").trim() == record.mergeCommit) { "Проверенный результат изменился" }
        clean(dir, TASK_COPY)
        require(ancestor(dir, record.targetCommit, record.mergeCommit)) { "Слияние больше не продолжает исходную ветку" }
        git(File(record.sourcePath), "merge", "--ff-only", "--no-autostash", "--no-overwrite-ignore", record.mergeCommit)
        checkpoint("delivered")
        check(delivered(record)) { "Слияние не подтверждено" }
    }

    // Repositories whose registrations of erased copies are not yet pruned; a failed prune is retried by the next reset.
    private val unpruned = ConcurrentHashMap.newKeySet<String>()

    override suspend fun eraseForReset() = withContext(Dispatchers.IO) {
        // Only a copy's own `.git` link names the repository that registered it, so it is read before deletion.
        root.listFiles().orEmpty().mapNotNullTo(unpruned) { copy -> registeringRepository(copy)?.path }
        deleteTree(root.toPath())
        AppLog.info("coding.worktree", "pool.erased", mapOf("result" to "reset", "count" to unpruned.size.toString()))
    }

    // Git keeps a deleted copy registered, and its branch counts as checked out there until the registration is pruned.
    override suspend fun pruneAfterReset() {
        var failure: Exception? = null
        for (path in unpruned.toList()) try {
            val source = File(path)
            if (source.isDirectory) {
                val request = UUID.randomUUID().toString()
                val owner = CodingProject("worktree-prune-$request", "", source.path, 0)
                val lease = checkNotNull(authority.acquire(owner, request)) { "Папка репозитория занята; ветки задач не освобождены" }
                try {
                    authority.owned(listOf(lease), CheckScope(owner.id, "reset", request, 0), "task-prune") { transport ->
                        withContext(Commands(transport)) { git(source, "worktree", "prune") }
                    }
                } finally { withContext(NonCancellable) { authority.release(lease) } }
            }
            unpruned.remove(path)
            AppLog.info("coding.worktree", "source.pruned", mapOf("result" to if (source.isDirectory) "pruned" else "missing"))
        } catch (error: Exception) {
            // One unavailable repository must not keep the others' branches held; it stays for the next reset.
            if (error is CancellationException) throw error
            AppLog.error("coding.worktree", "source.prune.failed", error)
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }

    /** The main checkout, or the Git directory itself when it lives apart from one, of the repository behind [copy]. */
    private fun registeringRepository(copy: File): File? {
        val link = File(copy, ".git").takeIf { it.isFile && it.length() <= 16_384 } ?: return null
        val text = link.readText().trim().takeIf { it.startsWith("gitdir: ") && '\n' !in it && '\u0000' !in it } ?: return null
        val admin = File(text.removePrefix("gitdir: ")).let { if (it.isAbsolute) it else File(copy, it.path) }.canonicalFile
        val common = admin.parentFile?.takeIf { it.name == "worktrees" }?.parentFile ?: return null
        return if (common.name == ".git") common.parentFile else common
    }

    /**
     * Ветка задачи читается как название работы, а не как идентификатор: слаг запроса под
     * префиксом приложения. Короткий хеш добавляется только при занятом имени, чтобы слот пула
     * и история доставок оставались однозначными.
     */
    private suspend fun taskBranch(source: File, label: String, unique: String): String {
        val slug = asciiSlug(label, BRANCH_SLUG_LIMIT).ifBlank { "task-${unique.take(UNIQUE_SUFFIX_LENGTH)}" }
        val candidate = "$BRANCH_PREFIX$slug"
        val taken = probe(source, "show-ref", "--verify", "--quiet", "refs/heads/$candidate").first == 0
        return if (taken) "$candidate-${unique.take(UNIQUE_SUFFIX_LENGTH)}" else candidate
    }
    /** Subject берёт строку запроса задачи; тело коммита сохраняет идентификатор для журналов. */
    private fun commitSubject(record: TaskWorktree): String =
        commitSubjectText(record.label, COMMIT_SUBJECT_LIMIT).ifBlank { "MagicPaper task ${record.taskId}" }

    private fun managed(record: TaskWorktree, mustExist: Boolean = true): File = File(record.path).canonicalFile.also {
        require(it.parentFile == root.canonicalFile && (!mustExist || it.isDirectory)) { "Рабочая копия недоступна или не принадлежит приложению" }
    }
    /**
     * Отказ называет папку и конкретные пути: без них пользователь проверяет свой Git, не видит
     * изменений и не может отличить незакоммиченную правку в копии задачи от грязной исходной папки.
     */
    private suspend fun clean(dir: File, subject: String) {
        val dirty = git(dir, "status", "--porcelain", "--untracked-files=all")
        require(dirty.isBlank()) { "Сначала сохраните незакоммиченные изменения ($subject): ${dirtyEntries(dirty)}" }
        for (name in listOf("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply"))
            require(!markerPath(dir, name).exists()) { "Сначала завершите текущую Git-операцию ($subject): $name" }
    }
    private fun dirtyEntries(status: String): String {
        val entries = status.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        val shown = entries.take(DIRTY_ENTRY_LIMIT).joinToString("; ") { PlanningDiagnostics.redact(it).take(DIRTY_ENTRY_LENGTH) }
        return if (entries.size > DIRTY_ENTRY_LIMIT) "$shown; всего ${entries.size}" else shown
    }
    /** Git keeps per-operation markers in the linked worktree's own directory, not in the shared one. */
    private suspend fun markerPath(dir: File, name: String): File {
        val path = git(dir, "rev-parse", "--git-path", name).trim()
        return File(path).let { if (it.isAbsolute) it else File(dir, path) }
    }
    private suspend fun mergeInProgress(dir: File) = probe(dir, "rev-parse", "--verify", "--quiet", "MERGE_HEAD").first == 0
    private suspend fun rebaseInProgress(dir: File) = markerPath(dir, "rebase-merge").exists() || markerPath(dir, "rebase-apply").exists()
    /** Коммит, на который реально переносится незавершённый rebase; без доступной записи — предполагаемая вершина. */
    private suspend fun rebaseOntoCommit(dir: File, fallback: String): String {
        val onto = markerPath(dir, "rebase-merge").resolve("onto")
        return if (onto.isFile) onto.readText().trim().ifBlank { fallback } else fallback
    }
    private suspend fun unmerged(dir: File) = git(dir, "diff", "--name-only", "--diff-filter=U")
    private suspend fun head(dir: File) = git(dir, "rev-parse", "HEAD").trim()
    /** Commits of the destination branch that the task copy does not contain yet. */
    private suspend fun distance(dir: File, target: String): Int {
        val parts = git(dir, "rev-list", "--left-right", "--count", "HEAD...$target").trim().split(Regex("\\s+"))
        check(parts.size == 2) { "Не удалось измерить расстояние до ветки назначения" }
        return checkNotNull(parts.last().toIntOrNull()) { "Не удалось измерить расстояние до ветки назначения" }
    }
    private suspend fun rebaseOnto(dir: File, onto: String) = probe(dir, "-c", "core.editor=true", "rebase", onto)
    /**
     * Continues a rebase the agent repaired. False means unresolved conflicts remain in the copy;
     * a commit that became empty after resolution is skipped, as Git itself advises.
     */
    private suspend fun continueRebase(dir: File): Boolean {
        repeat(REBASE_STEP_LIMIT) {
            if (!rebaseInProgress(dir)) return true
            if (unmerged(dir).isNotBlank()) return false
            git(dir, "add", "-A")
            val (code, output) = probe(dir, "-c", "core.editor=true", "rebase", "--continue")
            if (code == 0) return true
            if (!rebaseInProgress(dir)) error("Не удалось продолжить перенос ветки\n${tail(output)}")
            if (unmerged(dir).isNotBlank()) return false
            val (skipped, skipOutput) = probe(dir, "rebase", "--skip")
            if (skipped != 0 && rebaseInProgress(dir)) error("Не удалось продолжить перенос ветки\n${tail(skipOutput)}")
        }
        error("Перенос ветки не завершён после $REBASE_STEP_LIMIT шагов")
    }
    /** The captured result must stay reachable; our own recorded pre-rebase point explains a rewritten history. */
    private suspend fun requireResult(dir: File, record: TaskWorktree) {
        val lost = { "Сохранённый результат задачи потерян из истории" }
        require(record.resultCommit.isNotBlank(), lost)
        if (ancestor(dir, record.resultCommit, "HEAD")) return
        val pre = preIntegrationRef(record)
        // This ref is created by this workspace immediately before it rewrites the task result.
        // It remains the authoritative proof even when a later destination rewrite means the
        // previous target is intentionally no longer an ancestor of the in-progress rebase HEAD.
        if (probe(dir, "show-ref", "--verify", "--quiet", pre).first == 0 &&
            ancestor(dir, record.resultCommit, pre)) return
        // Compatibility with a conflict started before pre-integration refs existed: while
        // rebase is active Git deliberately leaves the task branch at the captured result.
        val taskBranch = "refs/heads/${record.branch}"
        require(rebaseInProgress(dir) && probe(dir, "show-ref", "--verify", "--quiet", taskBranch).first == 0 &&
            ancestor(dir, record.resultCommit, taskBranch), lost)
    }
    private suspend fun preservePreIntegrationRef(dir: File, record: TaskWorktree) {
        val ref = preIntegrationRef(record)
        if (probe(dir, "show-ref", "--verify", "--quiet", ref).first != 0)
            git(dir, "update-ref", ref, head(dir))
    }
    private fun preIntegrationRef(record: TaskWorktree) = "refs/magicpaper/task-pre-integration-${hash(record.taskId)}"
    private fun checkFailureDetail(output: String, limit: Int = CHECK_OUTPUT_DETAIL): String {
        val safe = PlanningDiagnostics.redact(output)
        // Parallel Gradle tasks can print warnings after a failed test. A tail alone loses
        // the failing test's identity, and the next run overwrites its HTML/XML report.
        val failures = safe.lineSequence().filter { it.trimEnd().endsWith(" FAILED") }
            .take(6).joinToString("\n") { it.take(160) }
        if (failures.isEmpty()) return safe.takeLast(limit).trim()
        return (failures + "\n" + safe.takeLast(limit - failures.length - 1)).trim()
    }
    /** The program's file name, never its directory: the log names what ran, not where it lives. */
    private fun programName(executable: String) = executable.substringAfterLast('/').substringAfterLast('\\')
    private fun tail(output: String) = PlanningDiagnostics.redact(output.takeLast(CHECK_OUTPUT_DETAIL)).trim()
    private suspend fun ancestor(dir: File, before: String, after: String): Boolean {
        val code = probe(dir, "merge-base", "--is-ancestor", before, after).first
        check(code == 0 || code == 1) { "Не удалось проверить историю Git" }
        return code == 0
    }
    private suspend fun git(dir: File, vararg args: String): String {
        val (code, output) = probe(dir, *args)
        check(code == 0) { "Не удалось выполнить Git-операцию ${args.first()}" }
        return output
    }
    private suspend fun probe(dir: File, vararg args: String): Pair<Int, String> {
        val input = args.toList()
        val query = readQuery(input)
        val result = if (query != null) commands().known(dir, query, CheckPolicy.GIT_READ_ONLY)
        else commands().known(dir, listOf("git", "--no-pager", "-c", "user.name=MagicPaper",
            "-c", "user.email=tasks@localhost", "-c", "core.fsmonitor=false", "-c", "core.hooksPath=",
            "-c", "commit.gpgSign=false") + input, CheckPolicy.MANAGED_WORKTREE,
            mapOf("GIT_TERMINAL_PROMPT" to "0"))
        return result.code to result.output.toString(Charsets.UTF_8)
    }
    private fun readQuery(args: List<String>): List<String>? {
        val fixed = when (args) {
            listOf("--version") -> CheckGitReadQuery.VERSION
            listOf("rev-parse", "--show-toplevel") -> CheckGitReadQuery.ROOT
            listOf("rev-parse", "--is-inside-work-tree") -> CheckGitReadQuery.IS_WORKTREE
            listOf("symbolic-ref", "--short", "HEAD"), listOf("symbolic-ref", "--quiet", "--short", "HEAD") -> CheckGitReadQuery.BRANCH
            listOf("status", "--porcelain") -> CheckGitReadQuery.STATUS
            listOf("status", "--porcelain", "--untracked-files=all") -> CheckGitReadQuery.STATUS_ALL
            listOf("rev-parse", "--path-format=absolute", "--git-common-dir") -> CheckGitReadQuery.COMMON_DIRECTORY
            listOf("diff", "--name-only", "--diff-filter=U") -> CheckGitReadQuery.UNMERGED
            else -> null
        }
        if (fixed != null) return fixed.arguments()
        return when {
            args.size == 2 && args[0] == "rev-parse" -> CheckGitReferenceQuery.REVISION.arguments(args[1])
            args.size == 3 && args.take(2) == listOf("rev-parse", "--verify") -> CheckGitReferenceQuery.REVISION.arguments(args[2])
            args.size == 4 && args.take(3) == listOf("rev-parse", "--verify", "--quiet") -> CheckGitReferenceQuery.REVISION.arguments(args[3])
            args.size == 4 && args.take(3) == listOf("show-ref", "--verify", "--quiet") -> CheckGitReferenceQuery.EXISTS.arguments(args[3])
            args.size == 4 && args.take(2) == listOf("merge-base", "--is-ancestor") -> CheckGitReferenceQuery.ANCESTOR.arguments(args[2], args[3])
            args.size == 4 && args.take(3) == listOf("rev-list", "--left-right", "--count") -> {
                val pair = args[3].split("...")
                require(pair.size == 2)
                CheckGitReferenceQuery.DISTANCE.arguments(pair[0], pair[1])
            }
            args.size == 3 && args.take(2) == listOf("rev-parse", "--git-path") -> CheckGitReferenceQuery.MARKER.arguments(args[2])
            else -> null
        }
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}

private const val CHECK_OUTPUT_DETAIL = 2000
/** A TRACE entry keeps 2048 characters; the command and the output tail share them so the tail's last lines survive. */
private const val TRACE_COMMAND = 300
private const val TRACE_OUTPUT = 1400
private const val DIRTY_ENTRY_LIMIT = 8
private const val DIRTY_ENTRY_LENGTH = 160
private const val SOURCE_FOLDER = "исходная папка проекта"
private const val TASK_COPY = "рабочая копия задачи"
private const val REBASE_STEP_LIMIT = 64
private const val BRANCH_PREFIX = "magicpaper/worktree-"
private const val BRANCH_SLUG_LIMIT = 48
private const val COMMIT_SUBJECT_LIMIT = 100
private const val UNIQUE_SUFFIX_LENGTH = 8
