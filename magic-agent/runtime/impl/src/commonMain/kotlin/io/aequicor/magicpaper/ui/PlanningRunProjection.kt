package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.domain.ExecutionPhase
import io.aequicor.magicpaper.domain.PlanningMachine
import kotlinx.coroutines.flow.combine

/** Saved RUN intent describes the request; only the current owner grant describes live execution. */
internal fun planningRunUi(state: PlanningMachine.State, admission: PlanningMachine.RunRef?, failed: Boolean): PlanningRunUi {
    val plan = state.plan ?: return PlanningRunUi()
    val run = state.run
    val uncertain = failed || state.persistenceUnknown || state.pendingOperations.isNotEmpty() ||
        run?.phase == PlanningMachine.RunPhase.UNKNOWN
    val active = !uncertain && admission != null && admission == run?.ref &&
        run?.phase == PlanningMachine.RunPhase.RUNNING && plan.intent == ExecutionIntent.RUN && !plan.stopping
    val canContinue = !uncertain && !state.deleted && !plan.stopping && plan.phase != ExecutionPhase.COMPLETE &&
        run?.phase !in setOf(PlanningMachine.RunPhase.RUNNING, PlanningMachine.RunPhase.STOPPING,
            PlanningMachine.RunPhase.UNKNOWN, PlanningMachine.RunPhase.COMPLETE)
    return PlanningRunUi(active, canContinue, uncertain)
}

internal fun PlanningStore.runUiSnapshot(): Map<String, PlanningRunUi> = machineStates.value.mapValues { (id, state) ->
    planningRunUi(state, admittedRuns.value[id], failure.value != null)
}

internal fun PlanningStore.runUi() = combine(machineStates, admittedRuns, failure) { states, admissions, error ->
    states.mapValues { (id, state) -> planningRunUi(state, admissions[id], error != null) }
}
