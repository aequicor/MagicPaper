package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface PlanningWorkspace {
    suspend fun acquire(project: CodingProject): Boolean
    suspend fun release(project: CodingProject)
    suspend fun prepare(project: CodingProject, runId: String): PlanWorkspace
    suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt): StageAttempt
    suspend fun capture(attempt: StageAttempt): String
    suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt): Boolean
    suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt): Boolean
    suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace
    /** Must ensure a previous process cannot still write before another run starts. */
    suspend fun reconcile(attempt: StageAttempt)
    suspend fun validateIntegration(workspace: PlanWorkspace) = Unit
    suspend fun validateExecutionPath(project: CodingProject, path: String) = Unit
    /** A content fingerprint collected by the host; null means verification is unavailable. */
    suspend fun verificationSnapshot(path: String): String? = null
    suspend fun finishDeliveryConflict(path: String): Boolean = false
}