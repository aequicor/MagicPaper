package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Branch delivery is separate from the planner's legacy file transfer. All writes stay in managed copies until ff-only delivery.
 * Объединение с веткой назначения выполняется переносом (rebase): история ветки остаётся линейной,
 * а каждое прерывание либо завершается, либо откатывается к сохранённой точке до переноса.
 */
class GitTaskWorkspace(
    private val root: File = File(System.getProperty("user.home"), ".MagicPaper/task-worktrees"),
    private val checkpoint: (String) -> Unit = {},
    // Управляемая копия задачи проверяется без песочницы исследования: см. KDoc TaskWorktreeIntegrationChecks.
    private val checks: SessionIntegrationCheckRunner = TaskWorktreeIntegrationChecks(),
) : TaskWorkspace {
    override suspend fun availability(project: CodingProject): WorktreeAvailability = withContext(Dispatchers.IO) {
        val source = File(project.path)
        if (!source.isDirectory) return@withContext WorktreeAvailability(false, "Папка проекта недоступна")
        try {
            if (command(source, listOf("git", "--version")).first != 0)
                return@withContext WorktreeAvailability(false, "Git недоступен")
            if (probe(source, "rev-parse", "--is-inside-work-tree").second.trim() != "true")
                return@withContext WorktreeAvailability(false, "В папке нет Git-репозитория")
            if (probe(source, "symbolic-ref", "--quiet", "--short", "HEAD").first != 0)
                return@withContext WorktreeAvailability(false, "Выберите Git-ветку")
            if (probe(source, "rev-parse", "--verify", "HEAD").first != 0)
                return@withContext WorktreeAvailability(false, "В ветке ещё нет коммитов")
            WorktreeAvailability(true)
        } catch (e: IOException) {
            AppLog.info("coding.worktree", "capability.unavailable", mapOf("reason" to "git-process-unavailable"))
            WorktreeAvailability(false, "Git недоступен")
        }
    }

    override suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String): TaskWorktree = withContext(Dispatchers.IO) {
        val source = File(git(File(project.path), "rev-parse", "--show-toplevel").trim()).canonicalFile
        clean(source)
        val target = git(source, "symbolic-ref", "--short", "HEAD").trim()
        val base = git(source, "rev-parse", "HEAD").trim()
        val slot = File(root, hash(source.path + ":" + sessionId)).canonicalFile
        TaskWorktree(taskId, source.path, target, base, slot.path, taskBranch(source, label, hash(sessionId + ":" + taskId)),
            label = label)
    }

    override suspend fun open(record: TaskWorktree, previous: TaskWorktree?) = withContext(Dispatchers.IO) {
        val source = File(record.sourcePath)
        val dir = managed(record, mustExist = false)
        if (dir.exists()) {
            val branch = git(dir, "symbolic-ref", "--short", "HEAD").trim()
            if (branch == record.branch) {
                reconcile(record)
                require(git(dir, "rev-parse", "HEAD").trim() == record.baseCommit) { "Подготовленная ветка задачи изменилась" }
                clean(dir)
                return@withContext
            }
            require(record.reuseBranch.isNotBlank() && branch == record.reuseBranch) { "Рабочая копия принадлежит другой задаче" }
            clean(dir)
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

    override suspend fun reconcile(record: TaskWorktree) = withContext(Dispatchers.IO) {
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

    override suspend fun capture(record: TaskWorktree): String = withContext(Dispatchers.IO) {
        reconcile(record)
        val dir = managed(record)
        require(!mergeInProgress(dir) && !rebaseInProgress(dir)) { "Сначала разрешите конфликт объединения" }
        if (git(dir, "status", "--porcelain").isNotBlank()) {
            git(dir, "add", "-A")
            git(dir, "commit", "-m", commitSubject(record), "-m", "MagicPaper task: ${record.taskId}")
        }
        checkpoint("captured")
        git(dir, "rev-parse", "HEAD").trim()
    }

    override suspend fun target(record: TaskWorktree): String = withContext(Dispatchers.IO) {
        val source = File(record.sourcePath)
        require(git(source, "symbolic-ref", "--short", "HEAD").trim() == record.targetBranch) { "Верните исходную ветку ${record.targetBranch} и повторите слияние" }
        clean(source)
        git(source, "rev-parse", "HEAD").trim()
    }

    override suspend fun refresh(record: TaskWorktree): TaskWorktreeRefresh = withContext(Dispatchers.IO) {
        reconcile(record)
        val dir = managed(record)
        val target = "refs/heads/${record.targetBranch}"
        if (probe(dir, "rev-parse", "--verify", "--quiet", target).first != 0)
            return@withContext TaskWorktreeRefresh(note = "Ветка назначения ${record.targetBranch} недоступна")
        val tip = git(dir, "rev-parse", target).trim()
        // Незавершённое объединение уже меняет HEAD: расстояние в этот момент не измеряется.
        if (mergeInProgress(dir) || rebaseInProgress(dir))
            return@withContext TaskWorktreeRefresh(targetCommit = tip, note = "Сначала завершите объединение с веткой назначения")
        val behind = distance(dir, tip)
        if (behind == 0) return@withContext TaskWorktreeRefresh(behind, tip)
        // Правки агента не принадлежат приложению: копия с несохранёнными изменениями обновится только при слиянии.
        if (git(dir, "status", "--porcelain").isNotBlank())
            return@withContext TaskWorktreeRefresh(behind, tip, note = "В копии есть несохранённые изменения")
        // До запуска конфликт некому разрешать: неудача откатывается, задача остаётся на прежнем коммите.
        val before = head(dir)
        val (code, output) = if (ancestor(dir, "HEAD", tip)) probe(dir, "merge", "--ff-only", tip) else rebaseOnto(dir, tip)
        if (code != 0 || !ancestor(dir, tip, "HEAD")) {
            if (rebaseInProgress(dir)) git(dir, "rebase", "--abort")
            check(head(dir) == before) { "Не удалось откатить обновление рабочей копии\n${tail(output)}" }
            AppLog.debug("coding.worktree", "task.refresh.declined", mapOf("entityId" to record.taskId, "result" to code.toString()))
            return@withContext TaskWorktreeRefresh(behind, tip,
                note = "Изменения ветки назначения конфликтуют с задачей; объединение выполнится при слиянии")
        }
        checkpoint("refreshed")
        AppLog.info("coding.worktree", "task.refreshed", mapOf("entityId" to record.taskId, "result" to behind.toString()))
        TaskWorktreeRefresh(distance(dir, tip), tip, updated = true)
    }

    override suspend fun integrate(record: TaskWorktree): String? = withContext(Dispatchers.IO) {
        reconcile(record)
        val dir = managed(record)
        requireResult(dir, record)
        if (mergeInProgress(dir)) {
            // Слияние, оставленное предыдущей версией доставки, завершается, а не отбрасывается.
            if (unmerged(dir).isNotBlank()) return@withContext null
            git(dir, "add", "-A")
            git(dir, "commit", "--no-edit")
        } else {
            if (rebaseInProgress(dir)) {
                // Прерванный перенос без сохранённого CONFLICT не содержит разрешения конфликта: откат и повтор.
                if (record.phase != TaskWorktreePhase.CONFLICT) git(dir, "rebase", "--abort")
                else if (!continueRebase(dir)) return@withContext null
            }
            if (!ancestor(dir, record.targetCommit, "HEAD")) {
                clean(dir)
                if (ancestor(dir, "HEAD", record.targetCommit)) git(dir, "merge", "--ff-only", record.targetCommit)
                else {
                    // Точка до переноса сохраняется в общем репозитории: она доказывает, что результат переписан нами, а не потерян.
                    git(dir, "update-ref", preIntegrationRef(record), head(dir))
                    val (code, output) = rebaseOnto(dir, record.targetCommit)
                    if (code != 0) {
                        if (unmerged(dir).isNotBlank()) return@withContext null
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

    override suspend fun verify(record: TaskWorktree) = withContext(Dispatchers.IO) {
        val dir = managed(record)
        val before = verificationSnapshot(dir.path)
        for ((index, args) in record.checks.withIndex()) {
            require(args.isNotEmpty() && args.none { '\u0000' in it }) { "Некорректная команда проверки" }
            val checkId = "task-${record.taskId}-check-$index"
            val result = try { checks.run(dir.path, checkId, args) }
            finally { withContext(NonCancellable) { checks.abort(checkId); checks.reconcile(checkId) } }
            val code = result.exitCode
            // Ключи вне allowlist AppLog санитируются до `[redacted]`: идентификатор задачи и код выхода берём из разрешённых.
            AppLog.info("coding.worktree", "check.finished", mapOf("entityId" to record.taskId, "index" to index.toString(), "result" to code.toString()))
            check(code == 0 && result.blockedReason == null) {
                // Голый вердикт без причины вынуждает агента и пользователя угадывать; ограниченный хвост вывода уже санирован.
                val detail = tail(result.output)
                buildString {
                    append("Проверка результата завершилась с ошибкой. Исправьте изменения и повторите продолжение")
                    result.blockedReason?.let { append('\n').append(PlanningDiagnostics.redact(it)) }
                    if (detail.isNotEmpty()) append('\n').append(detail)
                }
            }
        }
        require(verificationSnapshot(dir.path) == before) { "Проверка изменила файлы задачи; нужна повторная приёмка" }
        clean(dir)
    }

    override suspend fun delivered(record: TaskWorktree): Boolean = withContext(Dispatchers.IO) {
        if (record.mergeCommit.isBlank() || !ancestor(File(record.sourcePath), record.mergeCommit, "refs/heads/${record.targetBranch}")) return@withContext false
        // Ref ancestry alone cannot prove that an interrupted checkout finished updating its files/index.
        target(record)
        true
    }

    override suspend fun deliver(record: TaskWorktree) = withContext(Dispatchers.IO) {
        if (delivered(record)) return@withContext
        if (target(record) != record.targetCommit) throw TaskDestinationChanged()
        val dir = managed(record)
        require(git(dir, "rev-parse", "HEAD").trim() == record.mergeCommit) { "Проверенный результат изменился" }
        clean(dir)
        require(ancestor(dir, record.targetCommit, record.mergeCommit)) { "Слияние больше не продолжает исходную ветку" }
        git(File(record.sourcePath), "merge", "--ff-only", "--no-autostash", "--no-overwrite-ignore", record.mergeCommit)
        checkpoint("delivered")
        check(delivered(record)) { "Слияние не подтверждено" }
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
    private suspend fun clean(dir: File) {
        require(git(dir, "status", "--porcelain", "--untracked-files=all").isBlank()) { "Сначала сохраните незакоммиченные изменения" }
        for (name in listOf("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply"))
            require(!markerPath(dir, name).exists()) { "Сначала завершите текущую Git-операцию" }
    }
    /** Git keeps per-operation markers in the linked worktree's own directory, not in the shared one. */
    private suspend fun markerPath(dir: File, name: String): File {
        val path = git(dir, "rev-parse", "--git-path", name).trim()
        return File(path).let { if (it.isAbsolute) it else File(dir, path) }
    }
    private suspend fun mergeInProgress(dir: File) = probe(dir, "rev-parse", "--verify", "--quiet", "MERGE_HEAD").first == 0
    private suspend fun rebaseInProgress(dir: File) = markerPath(dir, "rebase-merge").exists() || markerPath(dir, "rebase-apply").exists()
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
        require(probe(dir, "show-ref", "--verify", "--quiet", pre).first == 0 && ancestor(dir, record.resultCommit, pre) &&
            record.targetCommit.isNotBlank() && ancestor(dir, record.targetCommit, "HEAD"), lost)
    }
    private fun preIntegrationRef(record: TaskWorktree) = "refs/magicpaper/task-pre-integration-${hash(record.taskId)}"
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
    private suspend fun probe(dir: File, vararg args: String) = command(dir,
        listOf("git", "-c", "user.name=MagicPaper", "-c", "user.email=tasks@localhost", "-c", "core.hooksPath=", "-c", "commit.gpgSign=false") + args)

    /** File-backed output avoids pipe deadlocks. Cancellation joins the process before releasing its owner. */
    private suspend fun command(dir: File, args: List<String>): Pair<Int, String> {
        val output = File.createTempFile("magicpaper-task-", ".log")
        var process: Process? = null
        try {
            currentCoroutineContext().ensureActive()
            process = ProcessBuilder(args).directory(dir).redirectErrorStream(true).redirectOutput(output).start()
            process.outputStream.close()
            while (process.isAlive) { currentCoroutineContext().ensureActive(); delay(25) }
            return process.exitValue() to output.inputStream().use { it.readNBytes(1024 * 1024).toString(Charsets.UTF_8) }
        } finally {
            if (process?.isAlive == true) withContext(NonCancellable) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly(); process.waitFor()
            }
            output.delete()
        }
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}

private const val CHECK_OUTPUT_DETAIL = 2000
private const val REBASE_STEP_LIMIT = 64
private const val BRANCH_PREFIX = "magicpaper/worktree-"
private const val BRANCH_SLUG_LIMIT = 48
private const val COMMIT_SUBJECT_LIMIT = 100
private const val UNIQUE_SUFFIX_LENGTH = 8
