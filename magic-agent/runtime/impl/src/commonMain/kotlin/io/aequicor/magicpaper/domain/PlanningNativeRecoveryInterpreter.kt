package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.planning.command
import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.currentCoroutineContext

/** Correlates the plan's journal with the native owner's receipts; it owns no second state machine. */
internal class PlanningNativeRecoveryInterpreter(private val store: PlanningStore, private val runtime: CodingRuntime) {
    data class Admission(val session: CodingSession, val request: PlanningNativeRequest?) {
        fun binding(): NativeRunRecoveryBinding? = request?.previous?.let {
            NativeRunRecoveryBinding(it.attempt, it.noDispatch)
        }
    }

    suspend fun admit(intent: JournalRecord, expected: PlanningMachine.AttemptRef, session: CodingSession, profile: LlmProfile): Admission {
        val requestId = Id.new()
        val current = session.copy(pendingRun = (session.pendingRun ?: CodingRunCheckpoint(requestId, "")).copy(runId = requestId))
        if(runtime.recovery == null) return Admission(current, null)
        val state = checkNotNull(store.machineStates.value[intent.stream])
        val engine = session.engine ?: state.plan?.engine ?: legacyCodingEngine(profile)
        val subject = PlanJournalSubject.decode(intent.detail)
        val snapshot = checkNotNull(store.journalPlan(intent.stream))
        val ref = currentCoroutineContext()[PlanningRunContext]?.ref ?: checkNotNull(store.currentAdmission(intent.stream))
        val request = PlanningNativeRequest(ref, intent.seq, snapshot.journal.revision.resetEpoch, checkNotNull(PlanJournalOperation.of(intent.operation)),
            subject.stage, subject.attempt, expected, engine, session.id, requestId, state.nativeRecovery.pending(engine, session.id))
        store.command(intent.stream, PlanningMachine.Fact.NativeObserved(PlanningNativeFact.RequestAdmitted(request), stamp()))
        return Admission(current.copy(engine = engine), request)
    }

    suspend fun observeConsumption(request: PlanningNativeRequest?) {
        val previous = request?.previous ?: return
        val recovery = checkNotNull(runtime.recovery).inspect(request.sessionId)
        require(!recovery.persistenceUnknown) { "Журнал исполнителя не подтверждён" }
        val exact = recovery.consumptions.filter { it.acknowledgementId == previous.id }
        require(exact.size <= 1) { "Подтверждение использовано неоднозначно" }
        val proof = exact.singleOrNull() ?: return // Pre-native host failure must retain the unconsumed decision.
        store.command(request.ref.planId, PlanningMachine.Fact.NativeObserved(
            PlanningNativeFact.ConsumptionObserved(request.requestId, proof), stamp()))
    }

    /** A stream's Finished marker cannot substitute for the exact native owner's terminal receipt. */
    suspend fun verifyCompletion(request: PlanningNativeRequest?) {
        if (request == null) return
        val snapshot = checkNotNull(runtime.recovery).inspect(request.sessionId)
        val exact = snapshot.items.filter { it.ref.engine == request.engine && it.ref.sessionId == request.sessionId && it.ref.requestId == request.requestId }
        if (snapshot.persistenceUnknown || exact.isEmpty() || exact.map { it.ref }.distinct().size != exact.size ||
            exact.any { it.termination != NativeRunTermination.STOPPED || it.outcome == NativeRunOutcome.UNKNOWN } ||
            snapshot.noDispatch.any { it.proof.engine == request.engine && it.proof.sessionId == request.sessionId && it.proof.requestId == request.requestId })
            throw NativeRunRecoveryRequired(snapshot)
    }

    suspend fun authorizeReleases(planId: String, selected: List<PlanningNativeEvidence>) {
        val state = checkNotNull(store.machineStates.value[planId])
        val native = state.nativeRecovery
        val plan = checkNotNull(state.plan)
        val releases = selected.map { evidence ->
            val request = native.requests.single { it.requestId == evidence.requestId }
            require(request.requestId !in native.released && request.intentSeq in state.pendingOperations) { "Состояние восстановления изменилось" }
            require(native.decisions[request.requestId]?.evidence?.singleOrNull { it.requestId == request.requestId } == evidence)
            val acknowledgements = native.acknowledgements[request.requestId].orEmpty()
            val snapshot = checkNotNull(runtime.recovery).inspect(request.sessionId)
            require(this.evidence(request, snapshot) == evidence) { "Подтверждение исполнителя изменилось" }
            val exact = evidence.attempts.map { ref -> snapshot.items.single { it.ref == ref } }
            require(exact.all { it.termination == NativeRunTermination.STOPPED }) { "Остановка предыдущего исполнителя не подтверждена" }
            val known = exact.filter { it.outcome != NativeRunOutcome.UNKNOWN }.map { PlanningNativeKnownOutcome(it.ref, it.outcome) }
            val complete = if (evidence.noDispatch != null) acknowledgements.any { it.noDispatch?.proof == evidence.noDispatch }
                else evidence.attempts.all { ref -> acknowledgements.any { it.attempt?.predecessor == ref } || known.any { it.ref == ref } }
            require(complete) { "Нет подтверждения исполнителя" }
            val attempt = requireNotNull(if (request.stageId.isBlank()) plan.finalAttempt else plan.milestones.singleOrNull { it.id == request.stageId }?.attempts?.lastOrNull())
            require(attempt.id == request.attemptId) { "Попытка восстановления изменилась" }
            PlanningNativeRecoveryRelease(request.requestId, PlanningMachine.AttemptRef.from(attempt), known)
        }
        val missing = releases.filter { release -> native.releaseAuthorizations[release.requestId]?.let {
            require(it.copy(expected = release.expected) == release) { "Доказательство восстановления изменилось" }; it != release
        } ?: true }
        if (missing.isNotEmpty()) store.command(planId, PlanningMachine.Fact.NativeObserved(
            PlanningNativeFact.ReleaseAuthorized(missing), stamp()))
    }

    /** Read-only validation used only for an already saved, explicit plan recovery decision. */
    suspend fun verifyAuthorizedCleanup(planId: String, selected: List<PlanningNativeEvidence>, aliases: List<String>) {
        val native = checkNotNull(store.machineStates.value[planId]).nativeRecovery
        val owner = runtime.recovery ?: run { require(selected.isEmpty()); return }
        for (expected in selected) {
            val request = native.requests.single { it.requestId == expected.requestId }
            require(native.decisions[request.requestId]?.evidence?.singleOrNull { it.requestId == request.requestId } == expected &&
                native.releaseAuthorizations[request.requestId] != null) { "Решение восстановления не подтверждено" }
            require(evidence(request, owner.inspect(request.sessionId)) == expected) { "Доказательство исполнителя изменилось" }
        }
        val accepted = native.acknowledgements.values.flatten()
        for (alias in aliases.distinct()) {
            val snapshot = owner.inspect(alias)
            require(!snapshot.persistenceUnknown && snapshot.items.all { item ->
                item.termination == NativeRunTermination.STOPPED && (item.outcome != NativeRunOutcome.UNKNOWN ||
                    item.acknowledgement?.let { ack -> accepted.any { it.attempt == ack } } == true)
            } && snapshot.noDispatch.all { item ->
                item.acknowledgement?.let { ack -> accepted.any { it.noDispatch == ack } } == true
            }) { "Остановка и решение по предыдущим запускам не подтверждены" }
        }
    }

    suspend fun inspect(planId: String, pending: Set<Long>): List<PlanningNativeEvidence> {
        val state = checkNotNull(store.machineStates.value[planId])
        val requests = state.nativeRecovery.requests.filter { it.intentSeq in pending }
        if(requests.isEmpty()) return emptyList()
        val owner = checkNotNull(runtime.recovery) { "Восстановление исполнителя недоступно" }
        return requests.map { request -> evidence(request, owner.inspect(request.sessionId)) }
    }

    private fun evidence(request: PlanningNativeRequest, snapshot: NativeRunRecoverySnapshot): PlanningNativeEvidence {
        require(!snapshot.persistenceUnknown) { "Журнал исполнителя не подтверждён" }
        val attempts = snapshot.items.filter { it.ref.engine == request.engine && it.ref.sessionId == request.sessionId && it.ref.requestId == request.requestId }
        val notSent = snapshot.noDispatch.filter { it.proof.engine == request.engine && it.proof.sessionId == request.sessionId && it.proof.requestId == request.requestId }
        require(attempts.map { it.ref }.distinct().size == attempts.size && notSent.size <= 1 && (attempts.isNotEmpty()) != (notSent.isNotEmpty())) {
            "Нет точного подтверждения предыдущего запроса; повтор запрещён"
        }
        return PlanningNativeEvidence(request.requestId, attempts.map { it.ref }, notSent.singleOrNull()?.proof)
    }

    suspend fun acknowledge(planId: String, evidence: List<PlanningNativeEvidence>) {
        if(evidence.isEmpty()) return
        val owner = checkNotNull(runtime.recovery)
        for(expected in evidence) {
            var state = checkNotNull(store.machineStates.value[planId]).nativeRecovery
            val request = state.requests.single { it.requestId == expected.requestId }
            val decision = checkNotNull(state.decisions[request.requestId])
            observeConsumption(request)
            require(decision.evidence.single { it.requestId == request.requestId } == expected)
            var snapshot = owner.inspect(request.sessionId)
            require(evidence(request, snapshot) == expected) { "Подтверждение исполнителя изменилось" }
            for(ref in expected.attempts) {
                if(snapshot.items.single { it.ref == ref }.termination != NativeRunTermination.STOPPED) snapshot = owner.stop(ref)
            }
            require(evidence(request, snapshot) == expected)
            require(expected.attempts.all { ref -> snapshot.items.single { it.ref == ref }.termination == NativeRunTermination.STOPPED }) {
                "Остановка предыдущего исполнителя не подтверждена"
            }
            // Host resources (including command checks) have their own cleanup authority.
            stopResources(request.sessionId)
            snapshot = owner.inspect(request.sessionId)
            require(evidence(request, snapshot) == expected)
            val acknowledgements = expected.attempts.mapNotNull { ref ->
                val item = snapshot.items.single { it.ref == ref }
                require(item.termination == NativeRunTermination.STOPPED)
                if(item.outcome != NativeRunOutcome.UNKNOWN && item.acknowledgement == null) null
                else PlanningNativeAcknowledgement(attempt = item.acknowledgement?.also {
                    require(it.parentDecisionId == decision.id && it.predecessor == ref) { "Решение исполнителя принадлежит другой сверке" }
                } ?: owner.acknowledge(ref, decision.id))
            } + listOfNotNull(expected.noDispatch?.let { proof ->
                val item = snapshot.noDispatch.single { it.proof == proof }
                PlanningNativeAcknowledgement(noDispatch = item.acknowledgement?.also {
                    require(it.parentDecisionId == decision.id && it.proof == proof) { "Решение исполнителя принадлежит другой сверке" }
                } ?: owner.acknowledgeNoDispatch(proof, decision.id))
            })
            for(ack in acknowledgements) {
                state = checkNotNull(store.machineStates.value[planId]).nativeRecovery
                val saved = state.acknowledgements[request.requestId].orEmpty().firstOrNull { it.id == ack.id }
                if(saved == null) store.command(planId, PlanningMachine.Fact.NativeObserved(
                    PlanningNativeFact.Acknowledged(request.requestId, decision.id, ack), stamp()))
                else require(saved == ack) { "Сохранённое подтверждение изменилось" }
            }
        }
    }

    /** Termination is sufficient for Stop, but deliberately does not resolve external outcomes. */
    suspend fun stopResources(sessionId: String) {
        val owner = runtime.recovery
        owner?.inspect(sessionId)?.let { before ->
            require(!before.persistenceUnknown) { "Журнал исполнителя не подтверждён" }
            before.items.filter { it.termination != NativeRunTermination.STOPPED }.forEach { owner.stop(it.ref) }
        }
        try { runtime.reconcile(sessionId) }
        catch(unknown: NativeRunRecoveryRequired) {
            // A facade may stop at its first engine's UNKNOWN. Never use that partial exception as group proof.
            val all = checkNotNull(owner).inspect(sessionId)
            require(!all.persistenceUnknown && all.items.all { it.termination == NativeRunTermination.STOPPED }) {
                "Остановка исполнителя не подтверждена"
            }
        }
        owner?.inspect(sessionId)?.let { all ->
            require(!all.persistenceUnknown && all.items.all { it.termination == NativeRunTermination.STOPPED }) {
                "Остановка исполнителя не подтверждена"
            }
        }
    }

    /** Historical UNKNOWN remains historical after ACK; only its exact saved decision permits a fresh request. */
    suspend fun prepare(planId: String, sessionId: String) {
        try { runtime.reconcile(sessionId) }
        catch(unknown: NativeRunRecoveryRequired) {
            val all = checkNotNull(runtime.recovery).inspect(sessionId)
            val native = checkNotNull(store.machineStates.value[planId]).nativeRecovery
            val accepted = native.acknowledgements.values.flatten()
            require(!all.persistenceUnknown && all.items.all { item ->
                item.termination == NativeRunTermination.STOPPED && (item.outcome != NativeRunOutcome.UNKNOWN ||
                    item.acknowledgement?.let { ack -> accepted.any { it.attempt == ack } } == true)
            }) { "Предыдущая работа требует сверки" }
        }
    }

    private fun stamp() = PlanningMachine.Stamp(Id.new(), Id.now())
}
