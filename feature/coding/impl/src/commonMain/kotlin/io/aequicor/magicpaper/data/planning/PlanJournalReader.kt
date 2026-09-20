package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.domain.JournalEntryKind
import io.aequicor.magicpaper.domain.PlanIntentReconciliation
import io.aequicor.magicpaper.domain.PlanIntentOutcome
import io.aequicor.magicpaper.domain.PlanJournalOperation
import io.aequicor.magicpaper.domain.PlanJournalSubject
import io.aequicor.magicpaper.domain.planning.PlanStrategySelection
import io.aequicor.magicpaper.domain.planning.PlanRecoveryStrategy

/** The journal is opaque storage; its planning owner interprets the closed vocabulary. */
internal fun unsettledPlanIntents(records: List<JournalRecord>): List<JournalRecord> {
    val intents = linkedMapOf<Long, JournalRecord>()
    val settled = mutableSetOf<Long>()
    val outcomes = mutableMapOf<Long, PlanIntentOutcome>()
    val outcomeRecords = mutableMapOf<Long, Long>()
    val strategies = mutableMapOf<Long, PlanStrategySelection>()
    val strategyAttempts = mutableMapOf<Long, Long>()
    var previous = 0L
    val stream = records.firstOrNull()?.stream
    for (stored in records) {
        val record = stored.planEvidence()
        require(record.stream == stream && record.seq > previous) { "Invalid plan journal order" }
        previous = record.seq
        val operation = PlanJournalOperation.of(record.operation)
        when {
            operation?.kind == JournalEntryKind.INTENT -> intents[record.seq] = record
            operation == PlanJournalOperation.INTENT_OUTCOME -> {
                val outcome = PlanIntentOutcome.decode(record.detail)
                require(outcome.intentSeq in intents) { "Outcome has no preceding intent in this stream" }
                require(outcomes[outcome.intentSeq]?.let { it == outcome } != false) { "Conflicting intent outcomes" }
                outcome.strategySeq?.let { strategySeq ->
                    val selection = checkNotNull(strategies[strategySeq]) { "Outcome has no preceding strategy" }
                    require(strategySeq < outcome.intentSeq && selection.strategy == PlanRecoveryStrategy.EXISTING_BACKOFF &&
                        selection.metrics.operation == intents.getValue(outcome.intentSeq).operation &&
                        selection.stageId == PlanJournalSubject.decode(intents.getValue(outcome.intentSeq).detail).stage) { "Strategy belongs to another operation" }
                    require(strategyAttempts[strategySeq]?.let { it == outcome.intentSeq } != false) { "Strategy outcome already consumed" }
                    strategyAttempts[strategySeq] = outcome.intentSeq
                }
                outcomes[outcome.intentSeq] = outcome
                outcomeRecords[record.seq] = outcome.intentSeq
                settled += outcome.intentSeq
            }
            operation == PlanJournalOperation.STRATEGY_SELECTED -> {
                val selection = PlanStrategySelection.decode(record.detail)
                val source = checkNotNull(outcomeRecords[selection.sourceSeq]) { "Strategy has no preceding outcome" }
                require(intents.getValue(source).operation == selection.metrics.operation &&
                    strategies.values.none { it.sourceSeq == selection.sourceSeq }) { "Invalid strategy source" }
                strategies[record.seq] = selection
            }
            operation == PlanJournalOperation.INTENT_RECONCILED -> {
                val reconciliation = PlanIntentReconciliation.decode(record.detail)
                require(reconciliation.intentSeq in intents) { "Reconciliation has no preceding intent in this stream" }
                settled += reconciliation.intentSeq
            }
            // Legacy completions have no sequence reference. They can settle only the most
            // recent matching intent, never every earlier request of the same operation.
            operation == PlanJournalOperation.APPLY_COMPLETE || operation == PlanJournalOperation.STOP_CONFIRMED -> {
                val intentOperation = if (operation == PlanJournalOperation.APPLY_COMPLETE)
                    PlanJournalOperation.APPLY_INTENT else PlanJournalOperation.STOP_INTENT
                val matching = intents.values.filter { it.operation == intentOperation.wire }
                // One confirmed termination satisfies every preceding stop request.
                if (operation == PlanJournalOperation.STOP_CONFIRMED) settled += matching.map { it.seq }
                else matching.lastOrNull()?.let { settled += it.seq }
            }
        }
    }
    return intents.values.filterNot { it.seq in settled }
}
