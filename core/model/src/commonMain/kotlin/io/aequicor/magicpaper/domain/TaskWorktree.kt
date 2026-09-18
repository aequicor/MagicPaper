package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable
enum class TaskWorktreePhase { PREPARING, RUNNING, READY, CAPTURING, MERGING, CONFLICT, DELIVERING, COMPLETE }

/** A user task survives native generations, clarification and process restarts. */
@Serializable
data class TaskWorktree(
    val taskId: String,
    val sourcePath: String,
    val targetBranch: String,
    val baseCommit: String,
    val path: String,
    val branch: String,
    val phase: TaskWorktreePhase = TaskWorktreePhase.PREPARING,
    val resultCommit: String = "",
    val targetCommit: String = "",
    val mergeCommit: String = "",
    val handoffGeneration: Long? = null,
    val checks: List<List<String>> = emptyList(),
    val error: String? = null,
    val reuseBranch: String = "",
    val reuseCommit: String = "",
    /** Destination tip the copy was last brought onto; empty until the first update or integration. */
    val integratedCommit: String = "",
    /** Destination commits missing from the task copy at the last distance check. */
    val behindCommits: Int = 0,
    /** Why the copy was not brought onto the destination tip; null when it is up to date. */
    val refreshNote: String? = null,
    /** An unfinished transfer onto the destination branch stays in the copy: the conflict
     * is resolved on the working branch by the resumed agent, never in the source folder. */
    val pendingTransfer: Boolean = false,
    /** Output survives a crash between native completion and Git delivery. */
    val executionResponse: CodingMessage? = null,
    /**
     * Первая содержательная строка запроса задачи: источник осознанного имени ветки и коммита.
     * Пусто у legacy-записей; тогда имена откатываются к идентификатору задачи.
     */
    val label: String = "",
)

data class WorktreeAvailability(val available: Boolean, val reason: String? = null)

/**
 * Distance to the destination branch plus the outcome of a safe pre-run update.
 * `updated` is false when nothing had to change or when the copy must not be touched yet.
 * `pendingTransfer` means an unfinished transfer onto the destination branch stays in
 * the copy: its conflict is resolved on the working branch by the resumed agent.
 */
data class TaskWorktreeRefresh(
    val behind: Int = 0,
    val targetCommit: String = "",
    val updated: Boolean = false,
    val note: String? = null,
    val pendingTransfer: Boolean = false,
)
