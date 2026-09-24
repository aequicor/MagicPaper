package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.checks.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Git transport only. The checks owner journals release and proves group termination before returning bytes. */
internal class OwnedGitCommands(private val checks: CommandChecks, private val resource: String,
    private val scope: CheckScope, private val operationId: String,
    private val affectedResources: Set<String> = emptySet(),
    private val readOnly: Boolean = false,
    private val register: (CheckRef, Set<String>) -> Unit = { _, _ -> }) {
    private val sequence = AtomicInteger()
    /** A command that may change files went past admission: a later refusal no longer means that nothing ran. */
    @Volatile var changing = false
        private set
    suspend fun read(dir: File, query: CheckGitReadQuery): ByteArray = execute(dir, query.arguments(),
        CheckPolicy.GIT_READ_ONLY, emptyMap())
    suspend fun write(dir: File, arguments: List<String>, environment: Map<String, String>): ByteArray = execute(dir,
        listOf("git", "--no-pager", "-c", "user.name=MagicPaper", "-c", "user.email=planning@localhost",
            "-c", "core.fsmonitor=false", "-c", "core.hooksPath=") + arguments,
        CheckPolicy.MANAGED_WORKTREE, environment + ("GIT_TERMINAL_PROMPT" to "0"))
    /** Known nonzero Git exits remain values; missing completion is never converted to an exit code. */
    suspend fun known(dir: File, arguments: List<String>, policy: CheckPolicy,
        environment: Map<String, String> = emptyMap()): GitCommandResult {
        require(policy == CheckPolicy.GIT_READ_ONLY && isCheckGitReadArguments(arguments) ||
            policy == CheckPolicy.MANAGED_WORKTREE && !readOnly) { "Команда не разрешена этим захватом" }
        val (ref, result) = run(dir, arguments, policy, environment, CheckOutputMode.BINARY_STDOUT)
        val code = result.exitCode
        if (result.blockedReason != null || code == null) throw GitCommandUnavailable()
        return GitCommandResult(code, checks.readOutput(ref))
    }
    /** [spawnGranted] — the user allowed this exact command to start programs the containment otherwise refuses. */
    suspend fun check(dir: File, arguments: List<String>, spawnGranted: Boolean = false): CheckResult {
        require(!readOnly && arguments.isNotEmpty() && arguments.size <= 128 &&
            arguments.all { it.length <= 16_384 && '\u0000' !in it }) { "Некорректная команда проверки" }
        return run(dir, arguments, CheckPolicy.MANAGED_WORKTREE, emptyMap(), CheckOutputMode.TEXT, spawnGranted).second
    }
    private suspend fun execute(dir: File, arguments: List<String>, policy: CheckPolicy, environment: Map<String, String>): ByteArray {
        val result = known(dir, arguments, policy, environment)
        if (result.code != 0) throw GitCommandExit(result.code)
        return result.output
    }
    private suspend fun run(dir: File, arguments: List<String>, policy: CheckPolicy,
        environment: Map<String, String>, mode: CheckOutputMode, spawnGranted: Boolean = false): Pair<CheckRef, CheckResult> {
        val ref = CheckRef(scope, "$operationId:${sequence.incrementAndGet()}")
        val affected = affectedResources + dir.canonicalPath + gitMetadataResources(dir)
        register(ref, affected) // Before admission: release must never miss an uncertain command or destination.
        val writes = policy != CheckPolicy.GIT_READ_ONLY
        val result = try {
            checks.run(CheckCommand(ref, dir.canonicalPath, arguments, policy = policy,
                outputMode = mode, environment = environment, protectedResource = resource, affectedResources = affected,
                spawnGranted = spawnGranted))
        } catch (busy: CheckResourceBusy) { throw busy }
        catch (failure: Throwable) { if (writes) changing = true; throw failure }
        if (writes) changing = true
        return ref to result
    }
    companion object {
        fun readOnly(checks: CommandChecks, path: String): OwnedGitCommands {
            val resource = File(path).canonicalPath
            val request = UUID.randomUUID().toString()
            return OwnedGitCommands(checks, resource, CheckScope(checkWorkspaceId(resource), "git-read-$request", request, 0), "read", readOnly = true)
        }
    }
}
internal data class GitCommandResult(val code: Int, val output: ByteArray)
internal class GitCommandExit(val code: Int) : IllegalStateException("Не удалось выполнить Git-операцию (код $code)")
internal class GitCommandUnavailable : IllegalStateException("Git-операция недоступна; рабочая папка сохранена")
internal suspend fun <T> gitOrNull(action: suspend () -> T): T? = try { action() } catch (_: GitCommandExit) { null }

/** Git's worktree indirection is a filesystem value, not a process probe or authority grant. */
internal fun gitMetadataResources(directory: File): Set<String> {
    var current: File? = directory.canonicalFile
    while (current != null) {
        val marker = File(current, ".git")
        if (marker.exists()) {
            val git = if (marker.isDirectory) marker.canonicalFile else {
                require(marker.isFile && marker.length() <= 16_384) { "Недоступна привязка рабочей копии Git" }
                val text = marker.readText().trim()
                require(text.startsWith("gitdir: ") && '\n' !in text && '\u0000' !in text) { "Повреждена привязка рабочей копии Git" }
                val value = File(text.removePrefix("gitdir: "))
                (if (value.isAbsolute) value else File(current, value.path)).canonicalFile
            }
            val common = File(git, "commondir")
            if (!common.exists()) return setOf(git.canonicalPath)
            require(common.isFile && common.length() <= 16_384) { "Недоступно общее хранилище Git" }
            val text = common.readText().trim()
            require(text.isNotBlank() && '\n' !in text && '\u0000' !in text) { "Повреждено общее хранилище Git" }
            val value = File(text)
            return setOf(git.canonicalPath, (if (value.isAbsolute) value else File(git, text)).canonicalPath)
        }
        current = current.parentFile
    }
    return emptySet()
}
