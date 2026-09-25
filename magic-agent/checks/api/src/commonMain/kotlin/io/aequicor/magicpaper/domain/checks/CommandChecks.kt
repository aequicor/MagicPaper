package io.aequicor.magicpaper.domain.checks

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** The same process authority serves two policies; it never silently substitutes one for the other. */
@Serializable enum class CheckPolicy { PROTECTED_PROJECT, MANAGED_WORKTREE, METADATA_READ_ONLY, GIT_READ_ONLY }
@Serializable enum class CheckOutputMode { TEXT, BINARY_STDOUT }

@Serializable data class CheckScope(val projectId: String, val sessionId: String, val requestId: String, val generation: Long)
@Serializable data class CheckRef(val scope: CheckScope, val callId: String, val attempt: Int = 0)
@Serializable enum class CheckGitMetadataQuery { TRACKED_FILES, GIT_DIRECTORY, COMMON_DIRECTORY }
@Serializable data class CheckMetadataSource(val parent: CheckRef, val query: CheckGitMetadataQuery)

/** Fixed commands used by the owner's preparation. No arbitrary Git command gains this policy. */
fun CheckGitMetadataQuery.arguments(): List<String> = listOf("git", "--no-pager", "--no-optional-locks",
    "-c", "core.fsmonitor=false", "-c", "core.hooksPath=") + when (this) {
    CheckGitMetadataQuery.TRACKED_FILES -> listOf("ls-files", "-z", "--cached")
    CheckGitMetadataQuery.GIT_DIRECTORY -> listOf("rev-parse", "--absolute-git-dir")
    CheckGitMetadataQuery.COMMON_DIRECTORY -> listOf("rev-parse", "--git-common-dir")
}
/** Exact read-only Git commands; arbitrary options cannot widen this process grant. */
@Serializable enum class CheckGitReadQuery {
    ROOT, INDEX, HEAD, FILES, IS_WORKTREE, VERSION, BRANCH, STATUS, STATUS_ALL, COMMON_DIRECTORY, UNMERGED;
    fun arguments(): List<String> = listOf("git", "--no-pager", "--no-optional-locks",
        "-c", "core.fsmonitor=false", "-c", "core.hooksPath=") + when (this) {
        ROOT -> listOf("rev-parse", "--show-toplevel")
        INDEX -> listOf("ls-files", "--stage", "-z")
        HEAD -> listOf("rev-parse", "--verify", "HEAD")
        FILES -> listOf("ls-files", "--cached", "--others", "--exclude-standard", "-z")
        IS_WORKTREE -> listOf("rev-parse", "--is-inside-work-tree")
        VERSION -> listOf("--version")
        BRANCH -> listOf("symbolic-ref", "--quiet", "--short", "HEAD")
        STATUS -> listOf("status", "--porcelain")
        STATUS_ALL -> listOf("status", "--porcelain", "--untracked-files=all")
        COMMON_DIRECTORY -> listOf("rev-parse", "--path-format=absolute", "--git-common-dir")
        UNMERGED -> listOf("diff", "--name-only", "--diff-filter=U")
    }
}
/** Values, never arbitrary options. The same construction validates the persisted command on replay. */
enum class CheckGitReferenceQuery {
    REVISION, EXISTS, ANCESTOR, DISTANCE, MARKER;
    fun arguments(reference: String, other: String? = null): List<String> {
        fun ref(value: String): Boolean {
            if (value.isEmpty() || value.length > 1024 || value.first() == '-' || ".." in value ||
                value.any { it.isWhitespace() || it.code < 32 || it.code in 127..159 || it in ":\\[*?" }) return false
            // Fully qualified names are refs, not revision expressions. Other operands may
            // use HEAD~1 or commit^{tree}; each still occupies one non-option argument.
            if (!value.startsWith("refs/")) return true
            return value.none { it in "^~{}" } && "@{" !in value && !value.endsWith('.') &&
                value.split('/').all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith(".lock") }
        }
        require(ref(reference) && (other == null || ref(other))) { "Некорректная ссылка Git" }
        val tail = when (this) {
            REVISION -> { require(other == null); listOf("rev-parse", "--verify", "--end-of-options", reference) }
            EXISTS -> { require(other == null && reference.startsWith("refs/")); listOf("show-ref", "--verify", "--quiet", reference) }
            ANCESTOR -> { require(other != null); listOf("merge-base", "--is-ancestor", reference, other) }
            DISTANCE -> { require(other != null); listOf("rev-list", "--left-right", "--count", "$reference...$other") }
            MARKER -> { require(other == null && reference in setOf("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")); listOf("rev-parse", "--git-path", reference) }
        }
        return CheckGitReadQuery.HEAD.arguments().dropLast(3) + tail
    }
}

/** Exact grammar shared by admission and task-query adapters. No read-only shell escape. */
fun isCheckGitReadArguments(arguments: List<String>): Boolean {
    if (CheckGitReadQuery.entries.any { it.arguments() == arguments }) return true
    val prefix = CheckGitReadQuery.HEAD.arguments().dropLast(3)
    if (arguments.take(prefix.size) != prefix) return false
    val tail = arguments.drop(prefix.size)
    return try {
        when {
            tail.size == 4 && tail.take(3) == listOf("rev-parse", "--verify", "--end-of-options") ->
                CheckGitReferenceQuery.REVISION.arguments(tail[3]) == arguments
            tail.size == 4 && tail.take(3) == listOf("show-ref", "--verify", "--quiet") ->
                CheckGitReferenceQuery.EXISTS.arguments(tail[3]) == arguments
            tail.size == 4 && tail.take(2) == listOf("merge-base", "--is-ancestor") ->
                CheckGitReferenceQuery.ANCESTOR.arguments(tail[2], tail[3]) == arguments
            tail.size == 4 && tail.take(3) == listOf("rev-list", "--left-right", "--count") -> {
                val pair = tail[3].split("...")
                pair.size == 2 && CheckGitReferenceQuery.DISTANCE.arguments(pair[0], pair[1]) == arguments
            }
            tail.size == 3 && tail.take(2) == listOf("rev-parse", "--git-path") ->
                CheckGitReferenceQuery.MARKER.arguments(tail[2]) == arguments
            else -> false
        }
    } catch (_: IllegalArgumentException) { false }
}

@Serializable data class CheckCommand(val ref: CheckRef, val workspace: String, val arguments: List<String>,
    val subdirectory: String = ".", val policy: CheckPolicy = CheckPolicy.PROTECTED_PROJECT,
    val outputMode: CheckOutputMode = CheckOutputMode.TEXT, val environment: Map<String, String> = emptyMap(),
    val protectedResource: String? = null, val metadataSource: CheckMetadataSource? = null,
    val affectedResources: Set<String> = emptySet(),
    /**
     * The user allowed this managed-worktree command to start programs the way the containment otherwise forbids
     * (`posix_spawn` under Seatbelt). Its descendants can then leave the process group the stop proof watches.
     */
    val spawnGranted: Boolean = false)
val CheckCommand.resource: String get() = protectedResource ?: workspace
/** Exact affected directories, including cwd and the lease owner. Includes destinations not yet created. */
val CheckCommand.resources: Set<String> get() = affectedResources + workspace + resource
/** Private immutable binary stdout; neither its bytes nor a filesystem path enter the shared journal. */
@Serializable data class CheckOutputRef(val id: String, val bytes: Long, val digest: String) {
    companion object { const val MAX_BYTES = 32L * 1024 * 1024 }
}
/**
 * [spawnRefused]: the command failed and its output shows the containment refused to start a program. Only a grant
 * ([CheckCommand.spawnGranted]) can change that outcome, so the caller asks the user rather than the agent.
 */
@Serializable data class CheckResult(val output: String, val exitCode: Int?, val blockedReason: String? = null,
    val binaryOutput: CheckOutputRef? = null, val spawnRefused: Boolean = false)
data class CheckProgress(val ref: CheckRef, val output: String)

/** Opaque native receipt. The PID is diagnostic identity, never proof that a process group stopped. */
@Serializable data class CheckProcessReceipt(val id: String, val kind: String, val pid: Long,
    val authorityReceipt: String? = null)
@Serializable data class CheckCompletionProof(val receiptId: String, val groupStopped: String,
    val authorityRestored: String, val artifactsCommitted: String)

/** Application-lived owner. Opening/replaying a scope does not dispatch or inspect a native process. */
interface CommandChecks {
    val progress: Flow<CheckProgress>
    suspend fun run(command: CheckCommand): CheckResult
    /** Wait for live checks behind a resource refusal; false means no safe retry was established. */
    suspend fun awaitConflictingChecks(command: CheckCommand): Boolean
    /** True only when this exact unfinished ref is still running and has reached its call boundary. */
    suspend fun awaitActive(ref: CheckRef): Boolean
    suspend fun inspect(ref: CheckRef): CheckResult?
    /** Read-only saved-state query. Never stops a process, starts a probe or grants execution. */
    suspend fun unresolved(resource: String): Set<CheckRef> = error("Workspace process inspection is unavailable")
    /** Returns exact, size/digest-verified bytes only after the referenced command has completed. */
    suspend fun readOutput(ref: CheckRef): ByteArray = error("Binary command output is unavailable")
    fun abort(sessionId: String)
    fun abortAll()
    suspend fun reconcile(sessionId: String)
    suspend fun prepareForReset()
    /**
     * Only on the user's explicit consent to erase application data: drops every journal that no longer replays
     * or holds a check whose outcome is unknown and is not running now. Admission stays open, so a reset can still
     * reconcile sessions and release workspaces through ordinary reads that such a journal would refuse. Returns
     * how many journals were dropped.
     */
    suspend fun discardUnresolvable(): Int = 0
    suspend fun resumeAfterReset()
    suspend fun close()
}

class CheckOutcomeUnknown(cause: Throwable? = null) : IllegalStateException(
    "Завершение проверки не подтверждено. Повтор заблокирован до проверки её состояния.", cause)

/**
 * Refused before admission: a running command of another workspace declares a resource this one also affects — the
 * Git storage shared by copies of one repository, for one. Nothing was started, so the caller may simply retry later.
 */
class CheckResourceBusy : IllegalStateException("Общие файлы рабочей копии заняты проверкой другой сессии")
