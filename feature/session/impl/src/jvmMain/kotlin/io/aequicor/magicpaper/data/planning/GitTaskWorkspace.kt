package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Branch delivery is separate from the planner's legacy file transfer. All writes stay in managed copies until ff-only delivery. */
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

    override suspend fun describe(project: CodingProject, sessionId: String, taskId: String): TaskWorktree = withContext(Dispatchers.IO) {
        val source = File(git(File(project.path), "rev-parse", "--show-toplevel").trim()).canonicalFile
        clean(source)
        val target = git(source, "symbolic-ref", "--short", "HEAD").trim()
        val base = git(source, "rev-parse", "HEAD").trim()
        val slot = File(root, hash(source.path + ":" + sessionId)).canonicalFile
        TaskWorktree(taskId, source.path, target, base, slot.path, "codex/magicpaper/task-${hash(sessionId + ":" + taskId)}")
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
        require(git(dir, "symbolic-ref", "--short", "HEAD").trim() == record.branch) { "Ветка рабочей копии изменена" }
        require(File(git(dir, "rev-parse", "--path-format=absolute", "--git-common-dir").trim()).canonicalFile ==
            File(git(File(record.sourcePath), "rev-parse", "--path-format=absolute", "--git-common-dir").trim()).canonicalFile) { "Рабочая копия принадлежит другому репозиторию" }
        require(ancestor(dir, record.baseCommit, "HEAD")) { "История задачи больше не продолжает исходный коммит" }
    }

    override suspend fun capture(record: TaskWorktree): String = withContext(Dispatchers.IO) {
        reconcile(record)
        val dir = managed(record)
        require(probe(dir, "rev-parse", "--verify", "MERGE_HEAD").first != 0) { "Сначала разрешите конфликт слияния" }
        if (git(dir, "status", "--porcelain").isNotBlank()) {
            git(dir, "add", "-A")
            git(dir, "commit", "-m", "MagicPaper task ${record.taskId}")
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

    override suspend fun merge(record: TaskWorktree): String? = withContext(Dispatchers.IO) {
        reconcile(record)
        val dir = managed(record)
        if (probe(dir, "rev-parse", "--verify", "MERGE_HEAD").first == 0) {
            if (git(dir, "diff", "--name-only", "--diff-filter=U").isNotBlank()) return@withContext null
            git(dir, "add", "-A")
            git(dir, "commit", "--no-edit")
        }
        require(record.resultCommit.isNotBlank() && ancestor(dir, record.resultCommit, "HEAD")) { "Сохранённый результат задачи потерян из истории" }
        if (!ancestor(dir, record.targetCommit, "HEAD")) {
            clean(dir)
            if (probe(dir, "merge", "--no-edit", if (ancestor(dir, "HEAD", record.targetCommit)) "--ff-only" else "--no-ff", record.targetCommit).first != 0) {
                require(probe(dir, "rev-parse", "--verify", "MERGE_HEAD").first == 0) { "Не удалось подготовить слияние" }
                return@withContext null
            }
        }
        checkpoint("merged")
        git(dir, "rev-parse", "HEAD").trim()
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
                val tail = PlanningDiagnostics.redact(result.output.takeLast(CHECK_OUTPUT_DETAIL)).trim()
                buildString {
                    append("Проверка результата завершилась с ошибкой. Исправьте изменения и повторите продолжение")
                    result.blockedReason?.let { append('\n').append(PlanningDiagnostics.redact(it)) }
                    if (tail.isNotEmpty()) append('\n').append(tail)
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

    private fun managed(record: TaskWorktree, mustExist: Boolean = true): File = File(record.path).canonicalFile.also {
        require(it.parentFile == root.canonicalFile && (!mustExist || it.isDirectory)) { "Рабочая копия недоступна или не принадлежит приложению" }
    }
    private suspend fun clean(dir: File) {
        require(git(dir, "status", "--porcelain", "--untracked-files=all").isBlank()) { "Сначала сохраните незакоммиченные изменения" }
        for (name in listOf("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")) {
            val path = git(dir, "rev-parse", "--git-path", name).trim()
            val marker = File(path).let { if (it.isAbsolute) it else File(dir, path) }
            require(!marker.exists()) { "Сначала завершите текущую Git-операцию" }
        }
    }
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
