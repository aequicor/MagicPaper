package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.PlanStrategySelection
import io.aequicor.magicpaper.data.storage.JournalRecord
import kotlinx.coroutines.flow.StateFlow

class PlanningRevisionConflictException : IllegalStateException("План изменился; повторите правку")

class PlanningPersistenceException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Semantic plan owner. Checkpoint replacement is not a public command. */
interface PlanningStore : PlanningRepository {
    suspend fun revokeAdmissions()
    val failure: StateFlow<String?>
    val plans: StateFlow<List<Plan>>
    val machineStates: StateFlow<Map<String, PlanningMachine.State>>
    /** Process-local grants published after the corresponding durable command and checkpoint. */
    val admittedRuns: StateFlow<Map<String, PlanningMachine.RunRef>>
    suspend fun dispatch(id: String, input: PlanningMachine.Input): PlanningMachine.Transition
    fun currentAdmission(id: String): PlanningMachine.RunRef?
    suspend fun recover()
    suspend fun beginIntent(projectId: String, operation: PlanJournalOperation, stageId: String, attemptId: String, ref: PlanningMachine.RunRef): JournalRecord
    suspend fun markIntentUnknown(intent: JournalRecord)
    suspend fun finishIntent(intent: JournalRecord, status: PlanIntentStatus)
    suspend fun unsettled(stream: String): List<JournalRecord>
    suspend fun journalPlan(id: String): JournalPlanSnapshot?
    suspend fun recordStrategy(expected: JournalPlanSnapshot, selection: PlanStrategySelection, retryLimit: Int?): Plan?
    suspend fun reconcileIntents(expectedPlan: Plan, expected: List<JournalRecord>, resolving: Set<Long>, authority: PlanRecoveryAuthority,
        expectedJournal: io.aequicor.magicpaper.data.storage.JournalSnapshot? = null)
}

fun interface PlanningStoreFactory { fun create(knownSecrets: () -> Set<String>): PlanningStore }

/** The interpreter keeps rejected inputs visible to its existing error boundary. */
suspend fun PlanningStore.command(id: String, input: PlanningMachine.Input): Plan {
    val result = dispatch(id, input)
    result.rejection?.let { throw IllegalArgumentException(it.reason) }
    return checkNotNull(result.state.plan)
}
