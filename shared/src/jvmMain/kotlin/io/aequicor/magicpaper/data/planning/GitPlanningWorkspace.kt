package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Git plumbing uses a private index: neither HEAD nor the user's staging area is changed. */
class GitPlanningWorkspace(
    private val dataRoot: File = File(System.getProperty("user.home"), ".MagicPaper/planning"),
    private val checkpoint: (String) -> Unit = {},
) : PlanningWorkspace {
    companion object {
        private val mutationLocks = ConcurrentHashMap<String, Mutex>()
    }
    override suspend fun verificationSnapshot(path: String): String = io.aequicor.magicpaper.data.planning.verificationSnapshot(path)
    private data class ProjectLock(val path: String, val resources: Pair<RandomAccessFile, FileLock>)
    private val locks = mutableMapOf<String, ProjectLock>()
    private var storeOwner: Pair<RandomAccessFile, FileLock>? = null
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun root(project: CodingProject) = File(dataRoot, hash(File(project.path).canonicalPath.toByteArray()).take(24))
    override suspend fun acquire(project: CodingProject): Boolean = withContext(Dispatchers.IO) {
        synchronized(locks) {
            val path = File(project.path).canonicalPath
            if (project.id in locks || locks.values.any { it.path == path }) return@synchronized false
            // A failed close after releasing the storage lock must be reconciled before
            // this adapter can admit work under an invalid storage-owner handle.
            if (storeOwner?.second?.isValid == false) return@synchronized false
            if (storeOwner == null) {
                dataRoot.mkdirs()
                val ownerFile = RandomAccessFile(File(dataRoot, "storage-owner.lock"), "rw")
                val ownerLock = try { ownerFile.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (ownerLock == null) { ownerFile.close(); return@synchronized false }
                storeOwner = ownerFile to ownerLock
            }
            val dir = root(project).apply { mkdirs() }
            val file = RandomAccessFile(File(dir, "owner.lock"), "rw")
            val lock = try { file.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lock == null) {
                file.close()
                if (locks.isEmpty()) releaseStoreOwner()
                false
            } else { locks[project.id] = ProjectLock(path, file to lock); true }
        }
    }
    override suspend fun release(project: CodingProject) = withContext(Dispatchers.IO) {
        synchronized(locks) {
            val owned = locks[project.id]
            if (owned != null) {
                require(owned.path == File(project.path).canonicalPath) { "Рабочая папка не совпадает с владельцем блокировки" }
                releaseResources(owned.resources, "project-lock")
            }
            if (locks.size == if (owned == null) 0 else 1) releaseStoreOwner()
            // Keep both identity and resources on any failure, including a storage close
            // after the project lock was released. Retrying skips already released locks.
            if (owned != null) locks.remove(project.id)
        }
        Unit
    }

    private fun releaseStoreOwner() {
        storeOwner?.let { releaseResources(it, "store-lock") }
        storeOwner = null
    }

    private fun releaseResources(resources: Pair<RandomAccessFile, FileLock>, boundary: String) {
        val (file, lock) = resources
        if (lock.isValid) {
            checkpoint("$boundary-releasing")
            lock.release()
        }
        checkpoint("$boundary-released")
        file.close()
    }

    override suspend fun prepare(project: CodingProject, runId: String): PlanWorkspace = withContext(Dispatchers.IO) {
        val source = File(project.path).canonicalFile
        require(source.isDirectory && source.canWrite()) { "Папка проекта недоступна для записи" }
        val dir = File(root(project), safe(runId)).apply { mkdirs() }
        val saved = File(dir, "workspace.json")
        if (saved.exists()) return@withContext json.decodeFromString<PlanWorkspace>(saved.readText())
        val isGit = try { git(source, "rev-parse", "--is-inside-work-tree").trim() == "true" }
            catch (e: Exception) { if (File(source, ".git").exists()) throw e else false }
        if (!isGit) return@withContext PlanWorkspace(dir.path, source.path).also { write(saved, json.encodeToString(PlanWorkspace.serializer(), it)) }
        require(File(git(source, "rev-parse", "--show-toplevel").trim()).canonicalFile == source) {
            "Для Git-планирования выберите корневую папку репозитория"
        }
        val integration = File(dir, "integration")
        val baseFile = File(dir, "initial-base")
        val base = if (baseFile.exists()) baseFile.readText() else
            (if (integration.exists()) git(integration, "rev-parse", "HEAD").trim() else snapshot(source, dir))
                .also { write(baseFile, it) }
        checkpoint("initial-base-saved")
        if (!integration.exists()) addBranchWorktree(source, integration, base, "integration")
        checkpoint("integration-created")
        PlanWorkspace(dir.path, integration.path, base, git = true).also { write(saved, json.encodeToString(PlanWorkspace.serializer(), it)) }
    }

    override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt): StageAttempt = withContext(Dispatchers.IO) {
        if (!workspace.git) return@withContext attempt.copy(path = project.path)
        val dir = File(workspace.root, "stage-${safe(attempt.id)}")
        if (!dir.exists()) addBranchWorktree(File(project.path), dir,
            git(File(workspace.integrationPath), "rev-parse", "HEAD").trim(), "stage")
        attempt.copy(path = dir.path, baseCommit = git(dir, "rev-parse", "HEAD").trim())
    }

    override suspend fun capture(attempt: StageAttempt): String = serializedMutation(attempt.path) {
        if (attempt.baseCommit.isBlank()) return@serializedMutation ""
        val dir = managedDirectory(attempt.path)
        val ref = "refs/magicpaper/${safe(attempt.id)}"
        val tree = workingTree(dir, File(attempt.path).parentFile)
        val existing = runCatching { git(dir, "rev-parse", "--verify", ref).trim() }.getOrNull()
        if (existing != null) {
            require(git(dir, "rev-parse", "$existing^{tree}").trim() == tree) {
                "После фиксации этапа рабочая копия изменилась; сохранённый результат не перезаписан"
            }
            return@serializedMutation existing
        }
        val subject = attempt.report.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
            ?: "Результат этапа ${attempt.id}"
        val head = git(dir, "rev-parse", "HEAD").trim()
        require(runCatching { git(dir, "merge-base", "--is-ancestor", attempt.baseCommit, head) }.isSuccess) {
            "История рабочей копии больше не продолжает базу этапа; результат требует проверки"
        }
        // The baseline snapshot is a checkpoint, not a milestone. An unchanged milestone
        // references it without fabricating a commit or an acceptance decision.
        val commit = if (tree == git(dir, "rev-parse", "${attempt.baseCommit}^{tree}").trim()) attempt.baseCommit else
            // Reuse a completed agent commit; don't flatten or duplicate its recorded work.
            if (tree == git(dir, "rev-parse", "$head^{tree}").trim()) head else
                git(dir, "commit-tree", tree, "-p", head, "-m", subject, "-m", "MagicPaper attempt: ${attempt.id}").trim()
        // Compare-and-swap rejects competing captures instead of silently changing a receipt.
        git(dir, "update-ref", ref, commit, "")
        checkpoint("captured")
        commit
    }

    override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt): Boolean = serializedMutation(workspace.integrationPath) {
        if (!workspace.git || attempt.resultCommit.isBlank()) return@serializedMutation true
        val dir = managedDirectory(workspace.integrationPath)
        if (runCatching { git(dir, "merge-base", "--is-ancestor", attempt.resultCommit, "HEAD") }.isSuccess) return@serializedMutation true
        if (runCatching { git(dir, "rev-parse", "--verify", "MERGE_HEAD") }.isSuccess) return@serializedMutation false
        runCatching { git(dir, "merge", "--no-edit", "--no-ff", attempt.resultCommit) }.isSuccess
    }
    override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt): Boolean = serializedMutation(workspace.integrationPath) {
        val dir = managedDirectory(workspace.integrationPath)
        if (git(dir, "diff", "--name-only", "--diff-filter=U").isNotBlank()) return@serializedMutation false
        if (runCatching { git(dir, "rev-parse", "--verify", "MERGE_HEAD") }.isSuccess) {
            git(dir, "add", "-A"); git(dir, "commit", "--no-edit")
        } else if (git(dir, "status", "--porcelain").isNotBlank()) {
            git(dir, "add", "-A"); git(dir, "commit", "-m", "MagicPaper verified conflict repair")
        }
        runCatching { git(dir, "merge-base", "--is-ancestor", attempt.resultCommit, "HEAD") }.isSuccess
    }

    @Serializable private data class FileChange(val path: String, val before: String?, val after: String?, val blob: String?, val executable: Boolean = false)
    @Serializable private data class Transfer(val commit: String, val files: List<FileChange>)

    override suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace = serializedMutation(workspace.integrationPath) {
        if (!workspace.git) return@serializedMutation workspace.copy(applied = true)
        val source = File(project.path).canonicalFile
        val integration = managedDirectory(workspace.integrationPath)
        val manifest = File(workspace.root, "transfer.json")
        val transfer = if (manifest.exists()) json.decodeFromString<Transfer>(manifest.readText()) else {
            val baseFile = File(workspace.root, "delivery-base")
            val current = if (baseFile.exists()) baseFile.readText() else snapshot(source, File(workspace.root)).also { write(baseFile, it) }
            val now = snapshot(source, File(workspace.root))
            require(git(source, "rev-parse", "$current^{tree}").trim() == git(source, "rev-parse", "$now^{tree}").trim()) {
                "Исходная папка изменилась во время подготовки переноса; изменения сохранены, требуется согласование"
            }
            val finalDir = File(workspace.root, "delivery")
            if (!finalDir.exists()) git(source, "worktree", "add", "--detach", finalDir.path, current)
            val result = git(integration, "rev-parse", "HEAD").trim()
            if (runCatching { git(finalDir, "rev-parse", "--verify", "MERGE_HEAD") }.isSuccess ||
                runCatching { git(finalDir, "merge", "--no-edit", "--no-ff", result) }.isFailure)
                throw WorkspaceConflict(finalDir.path, "Конфликт с изменениями исходной папки; рабочая копия сохранена")
            val commit = git(finalDir, "rev-parse", "HEAD").trim()
            val paths = git(finalDir, "diff", "--name-only", "-z", current, commit).split('\u0000').filter { it.isNotBlank() }
            val changes = paths.map { path ->
                val target = checked(source, path)
                require(!Files.isSymbolicLink(target.toPath())) { "Перенос символьной ссылки требует проверки: $path" }
                val entry = git(finalDir, "ls-tree", commit, "--", path).trim()
                require(!entry.startsWith("120000") && !entry.startsWith("160000")) { "Специальный файл требует проверки: $path" }
                val bytes = if (entry.isBlank()) null else gitBytes(finalDir, listOf("show", "$commit:$path"))
                val blob = bytes?.let { hash(it) }
                if (bytes != null) writeBytes(File(workspace.root, "blob-$blob"), bytes)
                FileChange(path, target.takeIf { it.isFile }?.readBytes()?.let(::hash), bytes?.let(::hash), blob, entry.startsWith("100755"))
            }
            Transfer(commit, changes).also { write(manifest, json.encodeToString(Transfer.serializer(), it)) }
        }
        checkpoint("transfer-prepared")
        // Validate every file before any write; each write remains replay-safe after a crash.
        transfer.files.forEach { c ->
            val file = checked(source, c.path)
            require(!file.exists() || file.isFile) { "На месте файла появилась папка: ${c.path}" }
            val actual = file.takeIf { it.isFile }?.readBytes()?.let(::hash)
            require(actual == c.before || actual == c.after) { "Файл изменён пользователем: ${c.path}" }
        }
        transfer.files.forEach { c ->
            val file = checked(source, c.path)
            val actual = file.takeIf { it.isFile }?.readBytes()?.let(::hash)
            if (actual != c.after) {
                require(actual == c.before) { "Файл изменён во время переноса: ${c.path}" }
                if (c.blob == null) require(!file.exists() || file.delete()) { "Не удалось удалить ${c.path}" }
                else {
                    writeBytes(file, File(workspace.root, "blob-${c.blob}").readBytes())
                    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) file.setExecutable(c.executable, false)
                }
                checkpoint("transferred:${c.path}")
            }
        }
        workspace.copy(applied = true, appliedCommit = transfer.commit)
    }

    override suspend fun reconcile(attempt: StageAttempt) = withContext(Dispatchers.IO) {
        require(attempt.path.isBlank() || File(attempt.path).isDirectory) { "Рабочая копия этапа отсутствует: ${attempt.path}" }
    }
    override suspend fun validateIntegration(workspace: PlanWorkspace) = withContext(Dispatchers.IO) {
        if (workspace.git) require(git(File(workspace.integrationPath), "status", "--porcelain", "--untracked-files=no").isBlank()) {
            "Итоговая проверка изменила отслеживаемые файлы; просмотрите рабочую копию интеграции"
        }
    }
    override suspend fun finishDeliveryConflict(path: String): Boolean = serializedMutation(path) {
        val dir = managedDirectory(path)
        if (git(dir, "diff", "--name-only", "--diff-filter=U").isNotBlank()) return@serializedMutation false
        if (runCatching { git(dir, "rev-parse", "--verify", "MERGE_HEAD") }.isSuccess) {
            git(dir, "add", "-A"); git(dir, "commit", "--no-edit")
        } else if (git(dir, "status", "--porcelain").isNotBlank()) {
            git(dir, "add", "-A"); git(dir, "commit", "-m", "MagicPaper verified delivery repair")
        }
        true
    }

    private fun snapshot(source: File, dir: File): String {
        val head = runCatching { git(source, "rev-parse", "HEAD").trim() }.getOrNull()
        val tree = workingTree(source, dir)
        if (head != null && tree == git(source, "rev-parse", "$head^{tree}").trim()) return head
        val args = listOf("commit-tree", tree) + (head?.let { listOf("-p", it) } ?: emptyList()) +
            listOf("-m", "MagicPaper checkpoint: existing working copy (not an accepted milestone)")
        return gitBytes(source, args).toString(Charsets.UTF_8).trim()
    }

    private suspend fun <T> serializedMutation(path: String, action: () -> T): T = withContext(Dispatchers.IO) {
        mutationLocks.getOrPut(File(path).canonicalPath) { Mutex() }.withLock { action() }
    }

    private fun workingTree(source: File, dir: File): String {
        val index = File.createTempFile("index", ".tmp", dir).also { it.delete() }
        val env = mapOf("GIT_INDEX_FILE" to index.path)
        return try {
            val head = runCatching { git(source, "rev-parse", "HEAD").trim() }.getOrNull()
            gitBytes(source, listOf("read-tree", head ?: "--empty"), env)
            gitBytes(source, listOf("add", "-A"), env)
            gitBytes(source, listOf("write-tree"), env).toString(Charsets.UTF_8).trim()
        } finally { index.delete() }
    }

    private fun managedDirectory(path: String): File = File(path).canonicalFile.also {
        require(it.toPath().startsWith(dataRoot.canonicalFile.toPath()) && it != dataRoot.canonicalFile && it.isDirectory) {
            "Рабочая копия не принадлежит хранилищу планирования"
        }
    }

    /** Keep the existing worktree/ref mechanism; name only newly created worktrees.
     * Legacy detached worktrees are reopened unchanged. Git refuses a second checkout
     * of the same branch. The path hash avoids collisions between runs and project aliases. */
    private fun addBranchWorktree(source: File, destination: File, base: String, purpose: String) {
        val branch = "codex/magicpaper/$purpose-${hash(destination.canonicalPath.toByteArray()).take(24)}"
        val existing = runCatching { git(source, "rev-parse", "--verify", "refs/heads/$branch").trim() }.getOrNull()
        if (existing == null) git(source, "worktree", "add", "-b", branch, destination.path, base)
        else {
            require(existing == base) { "Ветка рабочей копии изменилась; требуется восстановление" }
            git(source, "worktree", "add", destination.path, branch)
        }
    }
    private fun checked(root: File, path: String): File {
        val raw = File(root, path).absoluteFile.toPath().normalize()
        val base = root.canonicalFile.toPath()
        require(raw.startsWith(base) && raw != base) { "Путь выходит за проект" }
        var cursor = base
        base.relativize(raw).forEach { segment ->
            cursor = cursor.resolve(segment)
            require(!Files.isSymbolicLink(cursor)) { "Перенос символьной ссылки требует проверки: $path" }
        }
        val result = raw.toFile().canonicalFile
        require(result.toPath().startsWith(root.canonicalFile.toPath()) && result != root) { "Путь выходит за проект" }
        return result
    }
    private fun git(dir: File, vararg args: String) = gitBytes(dir, args.toList()).toString(Charsets.UTF_8)
    private fun gitBytes(dir: File, args: List<String>, env: Map<String, String> = emptyMap()): ByteArray {
        val errors = File.createTempFile("magicpaper-git", ".log")
        try {
            val p = ProcessBuilder(listOf("git", "-c", "user.name=MagicPaper", "-c", "user.email=planning@localhost", "-c", "core.hooksPath=" ) + args)
                .directory(dir).redirectError(errors).apply { environment().putAll(env) }.start()
            p.outputStream.close()
            val bytes = p.inputStream.use { it.readBytes() }
            check(p.waitFor() == 0) { errors.readText().take(2000) }
            return bytes
        } finally { errors.delete() }
    }
    private fun safe(value: String) = value.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun write(file: File, value: String) = writeBytes(file, value.toByteArray(Charsets.UTF_8))
    private fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        val tmp = File.createTempFile("write", ".tmp", file.parentFile)
        try {
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { tmp.delete() }
    }
}
