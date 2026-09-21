package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.checks.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
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
    private val authority: GitWorkspaceAuthority,
    private val checkpoint: (String) -> Unit = {},
) : PlanningWorkspace {
    companion object {
        private val mutationLocks = ConcurrentHashMap<String, Mutex>()
    }
    override suspend fun validateExecutionPath(project: CodingProject, path: String, operation: WorkspaceOperation?) =
        reading(operation, project.path) { reader ->
            val source = File(project.path).canonicalFile
            val target = File(path).canonicalFile
            val sourceGit = gitOrNull { File(reader.read(source, CheckGitReadQuery.ROOT).decodeToString().trim()).canonicalFile }
            val targetGit = gitOrNull { File(reader.read(target, CheckGitReadQuery.ROOT).decodeToString().trim()).canonicalFile }
            if (sourceGit != null && sourceGit == targetGit || source == target && File(source, ".git").exists())
                throw UnsafePlanningWorkspace("Этот план использует пользовательскую Git-папку. Создайте план с отдельной рабочей копией; сохранённые результаты и исходники оставлены на месте.")
        }

    override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?): String = reading(operation, path) {
        io.aequicor.magicpaper.data.planning.verificationSnapshot(path, it)
    }
    private class OperationContext(val commands: OwnedGitCommands) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OperationContext>
    }
    private val checks get() = authority.checks
    private suspend fun <T> owned(operation: WorkspaceOperation, affected: Set<String> = emptySet(), action: suspend () -> T): T =
        authority.owned(listOf(operation.lease),
            CheckScope(operation.lease.ownerId, operation.lease.token, operation.lease.requestId, 0), operation.operationId, affected) { commands ->
            withContext(OperationContext(commands)) { action() }
        }
    private suspend fun <T> reading(operation: WorkspaceOperation?, path: String, action: suspend (OwnedGitCommands) -> T): T =
        if (operation == null) withContext(Dispatchers.IO) { action(OwnedGitCommands.readOnly(checks, path)) }
        else owned(operation) { action(checkNotNull(currentCoroutineContext()[OperationContext]).commands) }
    private fun requirePath(operation: WorkspaceOperation, path: String) {
        require(File(path).canonicalPath == operation.lease.canonicalPath) { "Захват принадлежит другой рабочей папке" }
    }
    private fun requireWorkspace(project: CodingProject, workspace: PlanWorkspace) {
        val directory = File(workspace.root).canonicalFile
        require(directory.parentFile == root(project).canonicalFile) { "Рабочая копия принадлежит другому проекту" }
        if (workspace.git) requireIntegration(workspace)
        else require(File(workspace.integrationPath).canonicalPath == File(project.path).canonicalPath) { "Рабочая папка проекта изменилась" }
    }
    private fun requireIntegration(workspace: PlanWorkspace) {
        val directory = File(workspace.root).canonicalFile
        require(directory.toPath().startsWith(dataRoot.canonicalFile.toPath()) &&
            File(workspace.integrationPath).canonicalFile == File(directory, "integration")) {
            "Путь интеграции не принадлежит сохранённой рабочей копии"
        }
    }
    private fun requireManagedTarget(operation: WorkspaceOperation, path: String) {
        val target = File(path).canonicalFile
        val held = File(operation.lease.canonicalPath)
        if (target == held) return
        val root = dataRoot.canonicalFile.toPath()
        // A plan holds its integration checkout while its stage copies run. Only copies in
        // that exact managed run share the grant; another plan/run cannot borrow it.
        require(held.name == "integration" && held.toPath().startsWith(root) &&
            target.parentFile == held.parentFile && target.name.startsWith("stage-")) {
            "Рабочая копия не принадлежит текущему захвату"
        }
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun root(project: CodingProject) = File(dataRoot, hash(File(project.path).canonicalPath.toByteArray()).take(24))

    override suspend fun acquire(project: CodingProject, requestId: String) = authority.acquire(project, requestId)
    override suspend fun release(lease: WorkspaceLease) = authority.release(lease)
    override suspend fun holderOf(path: String) = authority.holderOf(path)

    override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation): PlanWorkspace = owned(operation,
        setOf(project.path, File(root(project), safe(runId)).path, File(File(root(project), safe(runId)), "integration").path)) {
        requirePath(operation, project.path)
        val source = File(project.path).canonicalFile
        require(source.isDirectory && source.canWrite()) { "Папка проекта недоступна для записи" }
        val dir = File(root(project), safe(runId)).apply { mkdirs() }
        val saved = File(dir, "workspace.json")
        if (saved.exists()) return@owned json.decodeFromString<PlanWorkspace>(saved.readText()).also {
            requireWorkspace(project, it)
            require(File(it.root).canonicalFile == dir.canonicalFile) { "Сохранённая рабочая копия принадлежит другому запуску" }
        }
        val isGit = try { git(source, "rev-parse", "--is-inside-work-tree").trim() == "true" }
            catch (e: GitCommandExit) { if (File(source, ".git").exists()) throw e else false }
        if (!isGit) return@owned PlanWorkspace(dir.path, source.path).also { write(saved, json.encodeToString(PlanWorkspace.serializer(), it)) }
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

    override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation): StageAttempt = owned(operation,
        setOf(project.path, workspace.integrationPath, File(workspace.root, "stage-${safe(attempt.id)}").path)) {
        require(operation.lease.canonicalPath in setOf(File(project.path).canonicalPath, File(workspace.integrationPath).canonicalPath)) {
            "Захват принадлежит другому плану"
        }
        requireWorkspace(project, workspace)
        if (!workspace.git) return@owned attempt.copy(path = project.path)
        val dir = File(workspace.root, "stage-${safe(attempt.id)}")
        if (!dir.exists()) addBranchWorktree(File(project.path), dir,
            git(File(workspace.integrationPath), "rev-parse", "HEAD").trim(), "stage")
        attempt.copy(path = dir.path, baseCommit = git(dir, "rev-parse", "HEAD").trim())
    }

    override suspend fun capture(attempt: StageAttempt, operation: WorkspaceOperation): String = serializedMutation(attempt.path, operation, setOf(attempt.path)) {
        requireManagedTarget(operation, attempt.path)
        if (attempt.baseCommit.isBlank()) return@serializedMutation ""
        val dir = managedDirectory(attempt.path)
        val ref = "refs/magicpaper/${safe(attempt.id)}"
        val tree = workingTree(dir, File(attempt.path).parentFile)
        val existing = gitOrNull { git(dir, "rev-parse", "--verify", ref).trim() }
        if (existing != null) {
            require(git(dir, "rev-parse", "$existing^{tree}").trim() == tree) {
                "После фиксации этапа рабочая копия изменилась; сохранённый результат не перезаписан"
            }
            return@serializedMutation existing
        }
        val subject = attempt.report.lineSequence().firstOrNull { it.isNotBlank() }
            ?.let { commitSubjectText(it, STAGE_SUBJECT_LIMIT) }?.ifBlank { null }
            ?: "MagicPaper stage result ${attempt.id}"
        val head = git(dir, "rev-parse", "HEAD").trim()
        require((gitOrNull { git(dir, "merge-base", "--is-ancestor", attempt.baseCommit, head) } != null)) {
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

    override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation): Boolean = serializedMutation(workspace.integrationPath, operation, setOf(workspace.integrationPath, attempt.path)) {
        requirePath(operation, workspace.integrationPath)
        if (workspace.git) requireIntegration(workspace)
        if (!workspace.git || attempt.resultCommit.isBlank()) return@serializedMutation true
        val dir = managedDirectory(workspace.integrationPath)
        if ((gitOrNull { git(dir, "merge-base", "--is-ancestor", attempt.resultCommit, "HEAD") } != null)) return@serializedMutation true
        if ((gitOrNull { git(dir, "rev-parse", "--verify", "MERGE_HEAD") } != null)) return@serializedMutation false
        (gitOrNull { git(dir, "merge", "--no-edit", "--no-ff", attempt.resultCommit) } != null)
    }
    override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation): Boolean = serializedMutation(workspace.integrationPath, operation, setOf(workspace.integrationPath, attempt.path)) {
        requirePath(operation, workspace.integrationPath)
        if (workspace.git) requireIntegration(workspace)
        val dir = managedDirectory(workspace.integrationPath)
        if (git(dir, "diff", "--name-only", "--diff-filter=U").isNotBlank()) return@serializedMutation false
        if ((gitOrNull { git(dir, "rev-parse", "--verify", "MERGE_HEAD") } != null)) {
            git(dir, "add", "-A"); git(dir, "commit", "--no-edit")
        } else if (git(dir, "status", "--porcelain").isNotBlank()) {
            git(dir, "add", "-A"); git(dir, "commit", "-m", "MagicPaper verified conflict repair")
        }
        (gitOrNull { git(dir, "merge-base", "--is-ancestor", attempt.resultCommit, "HEAD") } != null)
    }

    @Serializable private data class FileChange(val path: String, val before: String?, val after: String?, val blob: String?, val executable: Boolean = false)
    @Serializable private data class Transfer(val commit: String, val files: List<FileChange>)

    override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation): PlanWorkspace = serializedMutation(workspace.integrationPath, operation,
        setOf(project.path, workspace.integrationPath, File(workspace.root, "delivery").path)) {
        requirePath(operation, project.path)
        requireWorkspace(project, workspace)
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
            if ((gitOrNull { git(finalDir, "rev-parse", "--verify", "MERGE_HEAD") } != null) ||
                (gitOrNull { git(finalDir, "merge", "--no-edit", "--no-ff", result) } == null))
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

    override suspend fun reconcile(attempt: StageAttempt, operation: WorkspaceOperation) = withContext(Dispatchers.IO) {
        authority.inspectHeld(operation)
        require(attempt.path.isBlank() || File(attempt.path).isDirectory) { "Рабочая копия этапа отсутствует" }
    }
    override suspend fun validateIntegration(workspace: PlanWorkspace, operation: WorkspaceOperation) = owned(operation) {
        requirePath(operation, workspace.integrationPath)
        if (workspace.git) requireIntegration(workspace)
        if (workspace.git) require(git(File(workspace.integrationPath), "status", "--porcelain", "--untracked-files=no").isBlank()) {
            "Итоговая проверка изменила отслеживаемые файлы; просмотрите рабочую копию интеграции"
        }
    }
    override suspend fun finishDeliveryConflict(path: String, operation: WorkspaceOperation): Boolean = serializedMutation(path, operation) {
        val sourceRoot = File(dataRoot, hash(operation.lease.canonicalPath.toByteArray()).take(24)).canonicalFile.toPath()
        require(File(path).canonicalPath == operation.lease.canonicalPath ||
            File(path).canonicalFile.toPath().startsWith(sourceRoot) && File(path).name == "delivery") {
            "Рабочая копия переноса принадлежит другому захвату"
        }
        val dir = managedDirectory(path)
        if (git(dir, "diff", "--name-only", "--diff-filter=U").isNotBlank()) return@serializedMutation false
        if ((gitOrNull { git(dir, "rev-parse", "--verify", "MERGE_HEAD") } != null)) {
            git(dir, "add", "-A"); git(dir, "commit", "--no-edit")
        } else if (git(dir, "status", "--porcelain").isNotBlank()) {
            git(dir, "add", "-A"); git(dir, "commit", "-m", "MagicPaper verified delivery repair")
        }
        true
    }

    private suspend fun snapshot(source: File, dir: File): String {
        val head = gitOrNull { git(source, "rev-parse", "HEAD").trim() }
        val tree = workingTree(source, dir)
        if (head != null && tree == git(source, "rev-parse", "$head^{tree}").trim()) return head
        val args = listOf("commit-tree", tree) + (head?.let { listOf("-p", it) } ?: emptyList()) +
            listOf("-m", "MagicPaper checkpoint: existing working copy (not an accepted milestone)")
        return gitBytes(source, args).toString(Charsets.UTF_8).trim()
    }

    private suspend fun <T> serializedMutation(path: String, operation: WorkspaceOperation, affected: Set<String> = setOf(path), action: suspend () -> T): T = owned(operation, affected) {
        mutationLocks.getOrPut(File(path).canonicalPath) { Mutex() }.withLock { action() }
    }

    private suspend fun workingTree(source: File, dir: File): String {
        val index = File.createTempFile("index", ".tmp", dir).also { it.delete() }
        val env = mapOf("GIT_INDEX_FILE" to index.path)
        var completed = false
        return try {
            val head = gitOrNull { git(source, "rev-parse", "HEAD").trim() }
            gitBytes(source, listOf("read-tree", head ?: "--empty"), env)
            gitBytes(source, listOf("add", "-A"), env)
            gitBytes(source, listOf("write-tree"), env).toString(Charsets.UTF_8).trim().also { completed = true }
        } finally {
            // An unknown or cancelled command may still refer to the private index. Retain it
            // with the journal evidence; deleting it is not native termination proof.
            if (completed) check(index.delete() || !index.exists()) { "Не удалось очистить временный индекс" }
        }
    }

    private fun managedDirectory(path: String): File = File(path).canonicalFile.also {
        require(it.toPath().startsWith(dataRoot.canonicalFile.toPath()) && it != dataRoot.canonicalFile && it.isDirectory) {
            "Рабочая копия не принадлежит хранилищу планирования"
        }
    }

    /** Keep the existing worktree/ref mechanism; name only newly created worktrees.
     * Legacy detached worktrees are reopened unchanged. Git refuses a second checkout
     * of the same branch. The path hash avoids collisions between runs and project aliases. */
    private suspend fun addBranchWorktree(source: File, destination: File, base: String, purpose: String) {
        val branch = "magicpaper/$purpose-${hash(destination.canonicalPath.toByteArray()).take(24)}"
        val existing = gitOrNull { git(source, "rev-parse", "--verify", "refs/heads/$branch").trim() }
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
    private suspend fun git(dir: File, vararg args: String) = gitBytes(dir, args.toList()).toString(Charsets.UTF_8)
    private suspend fun gitBytes(dir: File, args: List<String>, env: Map<String, String> = emptyMap()): ByteArray =
        checkNotNull(currentCoroutineContext()[OperationContext]) { "Git requires an exact workspace operation" }.commands.write(dir, args, env)
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

private const val STAGE_SUBJECT_LIMIT = 120
