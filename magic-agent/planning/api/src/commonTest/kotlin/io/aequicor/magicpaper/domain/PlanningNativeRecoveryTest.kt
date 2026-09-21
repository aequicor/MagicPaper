package io.aequicor.magicpaper.domain

import kotlin.test.*

class PlanningNativeRecoveryTest {
    private val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"), engine = CodingEngine.PI)
    private fun running(): PlanningMachine.State {
        val plan = Plan("plan", "project", "Goal", engine = CodingEngine.PI,
            milestones = listOf(Milestone("stage", "Stage", description = "Work", attempts = listOf(attempt))))
        val created = PlanningMachine.reduce(PlanningMachine.initial(plan.id), PlanningMachine.Intent.Create(plan, stamp("create"))).state
        return PlanningMachine.reduce(created, PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp("start"))).state
    }
    private fun request(state: PlanningMachine.State) = PlanningNativeRequest(checkNotNull(state.run).ref, 3, 0,
        PlanJournalOperation.AGENT_INTENT, "stage", attempt.id, PlanningMachine.AttemptRef.from(attempt),
        CodingEngine.PI, attempt.sessionId, "request")
    private fun observe(state: PlanningMachine.State, request: PlanningNativeRequest) = PlanningMachine.reduce(state,
        PlanningMachine.Fact.NativeObserved(PlanningNativeFact.RequestAdmitted(request), stamp(request.requestId)))
    private fun stamp(id: String) = PlanningMachine.Stamp(id, 1)

    @Test fun oneJournalIntentCannotAdmitASecondFreshNativeRequest() {
        val state = running()
        val request = request(state)
        val first = observe(state, request)
        assertNull(first.rejection)
        val duplicate = observe(first.state, request.copy(requestId = "another"))
        assertNotNull(duplicate.rejection)
        assertEquals(first.state, duplicate.state)
    }

    @Test fun changedAttemptEngineAndOperationAliasAreRejectedWithoutEffects() {
        val state = running()
        val request = request(state)
        for(invalid in listOf(request.copy(sessionId = "worker-merge"), request.copy(engine = CodingEngine.CODEX),
            request.copy(expected = request.expected.copy(turnIndex = request.expected.turnIndex + 1)),
            request.copy(operation = PlanJournalOperation.CAPTURE_INTENT))) {
            val result = observe(state, invalid)
            assertNotNull(result.rejection)
            assertEquals(state, result.state)
            assertIs<PlanningMachine.Effect.Reject>(result.effects.single())
        }
    }

    @Test fun missingRecoveryRequestIsRejectedInsteadOfEscapingTheReducer() {
        val state = running()
        val result = PlanningMachine.reduce(state, PlanningMachine.Fact.NativeObserved(
            PlanningNativeFact.ConsumptionObserved("missing", NativeRunRecoveryConsumption("ack", CodingEngine.PI, "worker", "missing")), stamp("fact")))
        assertNotNull(result.rejection)
        assertEquals(state, result.state)
    }

    private fun acknowledged(issue: PlanningIssue = PlanningRecoveryIssues.nativeUncertainty): PlanningMachine.State {
        val running = running()
        val request = request(running)
        val admitted = observe(running, request).state
        val plan = checkNotNull(admitted.plan)
        val proof = NativeRunNoDispatchProof(request.engine, request.sessionId, request.requestId, "proof", "epoch")
        val decision = PlanningNativeRecoveryDecision("decision", plan.revision, 4, 0, setOf(3),
            listOf(PlanningNativeEvidence(request.requestId, noDispatch = proof)))
        val ack = PlanningNativeAcknowledgement(noDispatch = NativeRunNoDispatchAcknowledgement("ack", proof, decision.id))
        return admitted.copy(plan = plan.copy(issue = issue, milestones = plan.milestones.map {
            it.copy(attempts = listOf(attempt.copy(error = issue)))
        }), run = admitted.run!!.copy(phase = PlanningMachine.RunPhase.INTERRUPTED), nativeRecovery = admitted.nativeRecovery.copy(
            decisions = mapOf(request.requestId to decision), acknowledgements = mapOf(request.requestId to listOf(ack))))
    }

    @Test fun exactNativeConfirmationClearsOnlyItsBlockerWithoutAdmittingWorkAndIsOneShot() {
        val state = acknowledged()
        val release = PlanningNativeRecoveryRelease("request", PlanningMachine.AttemptRef.from(attempt))
        val input = PlanningMachine.Fact.RecoveryConfirmed(stamp("confirm"), listOf(release))
        val result = PlanningMachine.reduce(state, input)
        assertNull(result.rejection)
        assertTrue(result.effects.isEmpty())
        assertEquals(state.run, result.state.run)
        val plan = checkNotNull(result.state.plan)
        assertNull(plan.issue)
        assertNull(plan.milestones.single().attempts.last().error)
        assertEquals(listOf(attempt.id), plan.milestones.single().attempts.map { it.id })
        assertNotNull(PlanningMachine.reduce(result.state, input.copy(stamp = stamp("repeat"))).rejection)
    }

    @Test fun nativeConfirmationPreservesUnrelatedUncertaintyAndRejectsMissingOrStaleProof() {
        val unrelated = PlanningIssue(IssueKind.UNCERTAIN, "Отдельный результат команды неизвестен", requiresUser = true)
        val state = acknowledged(unrelated)
        val input = PlanningMachine.Fact.RecoveryConfirmed(stamp("confirm"), listOf(
            PlanningNativeRecoveryRelease("request", PlanningMachine.AttemptRef.from(attempt))))
        val result = PlanningMachine.reduce(state, input)
        assertNull(result.rejection)
        val plan = checkNotNull(result.state.plan)
        assertEquals(unrelated, plan.issue)
        assertEquals(unrelated, plan.milestones.single().attempts.last().error)
        assertNotNull(PlanningMachine.reduce(state.copy(nativeRecovery = state.nativeRecovery.copy(acknowledgements = emptyMap())), input).rejection)
        assertNotNull(PlanningMachine.reduce(state, input.copy(native = listOf(input.native.single().copy(
            expected = input.native.single().expected.copy(turnIndex = 2))))).rejection)
        assertNotNull(PlanningMachine.reduce(state.copy(nativeRecovery = state.nativeRecovery.copy(requests = state.nativeRecovery.requests +
            state.nativeRecovery.requests.single().copy(requestId = "newer", intentSeq = 9))), input).rejection)
    }

    @Test fun mixedAttemptRecoveryRequiresExactKnownOutcomesAndAcknowledgesOnlyUnknownAttempts() {
        val initial = acknowledged()
        val first = NativeRunRecoveryRef(CodingEngine.PI, attempt.sessionId, "request", 0)
        val last = first.copy(attempt = 1)
        val decision = initial.nativeRecovery.decisions.getValue("request").copy(evidence = listOf(
            PlanningNativeEvidence("request", attempts = listOf(first, last))))
        val ack = PlanningNativeAcknowledgement(attempt = NativeRunRecoveryAcknowledgement("ack", last, decision.id))
        val state = initial.copy(nativeRecovery = initial.nativeRecovery.copy(decisions = mapOf("request" to decision),
            acknowledgements = mapOf("request" to listOf(ack))))
        val release = PlanningNativeRecoveryRelease("request", PlanningMachine.AttemptRef.from(attempt),
            listOf(PlanningNativeKnownOutcome(first, NativeRunOutcome.SUCCEEDED)))
        fun confirm(value: PlanningNativeRecoveryRelease) = PlanningMachine.reduce(state,
            PlanningMachine.Fact.RecoveryConfirmed(stamp("confirm"), listOf(value)))
        assertNull(confirm(release).rejection)
        assertNull(confirm(release).state.plan!!.milestones.single().attempts.last().error)
        assertNotNull(confirm(release.copy(known = emptyList())).rejection)
        assertNotNull(confirm(release.copy(known = release.known + release.known)).rejection)
        assertNotNull(confirm(release.copy(known = listOf(release.known.single().copy(ref = first.copy(requestId = "foreign"))))).rejection)
        assertNotNull(PlanningMachine.reduce(state.copy(nativeRecovery = state.nativeRecovery.copy(acknowledgements = emptyMap())),
            PlanningMachine.Fact.RecoveryConfirmed(stamp("confirm"), listOf(release))).rejection)
        assertFailsWith<IllegalArgumentException> { PlanningNativeKnownOutcome(first, NativeRunOutcome.UNKNOWN) }
        assertNotNull(PlanningMachine.reduce(initial, PlanningMachine.Fact.RecoveryConfirmed(stamp("confirm"), listOf(release))).rejection,
            "NoDispatch cannot be combined with attempt outcomes")
    }

    @Test fun savedReleaseIsProjectedOnlyAfterExactIntentSettlementAndNeverAdmitsWorkOnReplay() {
        val state = acknowledged().let { it.copy(pendingOperations = setOf(3), run = it.run!!.copy(phase = PlanningMachine.RunPhase.UNKNOWN)) }
        val release = PlanningNativeRecoveryRelease("request", PlanningMachine.AttemptRef.from(attempt))
        val authorized = PlanningMachine.reduce(state, PlanningMachine.Fact.NativeObserved(
            PlanningNativeFact.ReleaseAuthorized(listOf(release)), stamp("authorize")))
        assertNull(authorized.rejection)
        assertEquals(state.plan, authorized.state.plan)
        assertTrue(authorized.effects.isEmpty())
        val restored = PlanningMachine.reduce(authorized.state, PlanningMachine.Fact.Restored(stamp("restore")))
        assertEquals(PlanningRecoveryIssues.nativeUncertainty, restored.state.plan!!.milestones.single().attempts.last().error)
        assertTrue(restored.effects.isEmpty())
        val settled = PlanningMachine.reduce(restored.state, PlanningMachine.Fact.EvidenceObserved(emptySet(), stamp("settled")))
        assertNull(settled.rejection)
        assertNull(settled.state.plan!!.milestones.single().attempts.last().error)
        assertEquals(PlanningMachine.RunPhase.INTERRUPTED, settled.state.run!!.phase)
        assertTrue(settled.effects.isEmpty())
        assertEquals(setOf("request"), settled.state.nativeRecovery.released)
        val repeated = PlanningMachine.reduce(settled.state, PlanningMachine.Fact.EvidenceObserved(emptySet(), stamp("again")))
        assertEquals(settled.state, repeated.state)
        assertTrue(repeated.effects.isEmpty())
        val changed = requireNotNull(authorized.state.plan).let { plan -> authorized.state.copy(plan = plan.copy(milestones = plan.milestones.map { stage ->
            stage.copy(attempts = stage.attempts.map { it.copy(interrupted = true) })
        })) }
        val obsolete = PlanningMachine.reduce(changed, PlanningMachine.Fact.EvidenceObserved(emptySet(), stamp("obsolete")))
        assertNull(obsolete.rejection, "A stale projection must not prevent reading the journal")
        assertEquals(PlanningRecoveryIssues.nativeUncertainty, obsolete.state.plan!!.milestones.single().attempts.last().error)
        assertTrue(obsolete.state.nativeRecovery.released.isEmpty())
        assertTrue(obsolete.effects.isEmpty())
        val partial = PlanningMachine.reduce(authorized.state.copy(pendingOperations = setOf(3, 8)), PlanningMachine.Fact.EvidenceObserved(setOf(8), stamp("partial")))
        assertEquals(PlanningRecoveryIssues.nativeUncertainty, partial.state.plan!!.milestones.single().attempts.last().error)
        assertTrue(partial.state.nativeRecovery.released.isEmpty())
        val noAuthorization = PlanningMachine.reduce(state, PlanningMachine.Fact.EvidenceObserved(emptySet(), stamp("unproven")))
        assertEquals(PlanningRecoveryIssues.nativeUncertainty, noAuthorization.state.plan!!.milestones.single().attempts.last().error)
    }
}
