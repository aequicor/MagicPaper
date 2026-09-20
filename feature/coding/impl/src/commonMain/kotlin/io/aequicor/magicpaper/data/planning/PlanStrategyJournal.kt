package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.data.storage.JournalSnapshot
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.*

internal data class JournalPlanSnapshot(val plan: Plan, val journal: JournalSnapshot)
internal data class StrategyIntent(val record: JournalRecord, val runId: String, val stageId: String)
internal data class PlanStrategyFacts(
    val samples: List<PlanStrategySample>,
    val selections: List<Pair<JournalRecord, PlanStrategySelection>>,
    val intents: Map<Long, StrategyIntent>,
    val consumed: Set<Long>,
)

/** Evidence with no original run identity is readable for recovery, but not classifier statistics. */
internal fun planStrategyFacts(records: List<JournalRecord>): PlanStrategyFacts {
    unsettledPlanIntents(records)
    var plan: Plan? = null
    val intents = mutableMapOf<Long, StrategyIntent>()
    val outcomes = linkedMapOf<Long, PlanStrategySample>()
    val selections = mutableListOf<Pair<JournalRecord, PlanStrategySelection>>()
    val consumed = mutableSetOf<Long>()
    records.forEach { stored ->
        PlanJournalCommit.from(stored)?.let { plan = projectPlanState(plan, it.state) }
        val record = stored.planEvidence()
        val operation = PlanJournalOperation.of(record.operation)
        when {
            operation?.kind == JournalEntryKind.INTENT -> intents[record.seq] = StrategyIntent(record,
                plan?.runId.orEmpty(), PlanJournalSubject.decode(record.detail).stage)
            operation == PlanJournalOperation.INTENT_OUTCOME -> {
                val outcome = PlanIntentOutcome.decode(record.detail)
                outcome.strategySeq?.let { consumed += it }
                val intent = checkNotNull(intents[outcome.intentSeq])
                if (intent.runId.isNotBlank()) outcomes.putIfAbsent(outcome.intentSeq, PlanStrategySample(record.seq, outcome.intentSeq,
                    intent.runId, intent.record.operation, intent.stageId, outcome.status,
                    if (intent.record.at >= 0 && record.at >= intent.record.at) record.at - intent.record.at else null))
            }
            operation == PlanJournalOperation.STRATEGY_SELECTED -> selections += record to PlanStrategySelection.decode(record.detail)
        }
    }
    return PlanStrategyFacts(outcomes.values.toList(), selections, intents, consumed)
}

/** Exactly the next matching admitted attempt reports the chosen strategy's real outcome. */
internal fun PlanStrategyFacts.strategyFor(intent: JournalRecord): Long? {
    val target = intents[intent.seq] ?: return null
    return selections.lastOrNull { (record, selection) ->
        record.seq < intent.seq && record.seq !in consumed && selection.runId == target.runId &&
            selection.stageId == target.stageId && selection.metrics.operation == intent.operation &&
            selection.strategy == PlanRecoveryStrategy.EXISTING_BACKOFF &&
            intents.values.firstOrNull { candidate -> candidate.record.seq > record.seq && candidate.runId == selection.runId &&
                candidate.stageId == selection.stageId && candidate.record.operation == selection.metrics.operation }?.record?.seq == intent.seq
    }?.first?.seq
}
