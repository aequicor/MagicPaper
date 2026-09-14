package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

interface PlanningExecutionHooks {
    suspend fun awaitReady() = Unit
    suspend fun recoverAssignments(plan: Plan): Plan = plan
    suspend fun blockedStages(plan: Plan): Set<String> = emptySet()
    suspend fun prepareSessions(plan: Plan)
    suspend fun instructions(plan: Plan, stage: Milestone, attempt: StageAttempt): String
    suspend fun started(plan: Plan, stage: Milestone, attempt: StageAttempt) = Unit
    suspend fun verified(plan: Plan, stage: Milestone, attempt: StageAttempt, record: AcceptanceRecord, reviewer: String) = Unit
    suspend fun finished(plan: Plan, stage: Milestone, attempt: StageAttempt): StageTurnDecision
}