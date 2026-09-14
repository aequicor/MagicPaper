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
    /** Output survives a crash between native completion and Git delivery. */
    val executionResponse: CodingMessage? = null,
)

data class WorktreeAvailability(val available: Boolean, val reason: String? = null)
