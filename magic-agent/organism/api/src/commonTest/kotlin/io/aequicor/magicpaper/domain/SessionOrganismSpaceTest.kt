package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.SessionOrganismMachine.Effect
import io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact
import io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent
import io.aequicor.magicpaper.domain.tools.ToolRole
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.Step
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The representatives of [SessionOrganismSpace], kept here rather than in the api so a shipped binary carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is what the `internal
 * constructor` on `State` is there to enforce. Every organism has one root session, `root`, under generation 1, and the input
 * values address it (or the records built around it) by the ids below; the deleted organism is the one exception, because
 * deleting history bumps the generation.
 *
 * Most positions stand on a root running under a CODE authority. The three that concern an immunity intervention cannot: an
 * intervention needs a diagnosis, a diagnosis needs an immunity session, and only a PLANNING root has one. They are built on a
 * PLANNING root, and an input that carries the CODE authority reads there as refused for its mode.
 */
class SessionOrganismSpaceTest {
    private var sequence = 0
    private fun stamp() = SessionOrganismMachine.Stamp("s${sequence++}", 1000)

    private val rules = PlanningRulesSnapshot("1", "rules")
    private val rootSession = CodingSession("root", "project", "Root", 1, runtimeGeneration = 1, planningRulesSnapshot = rules)
    private val planningSession = rootSession.copy(planningMode = true)
    private val scope = SessionAuthority("project", "root", "root", 1, CodingInteractionMode.CODE)
    private val planningScope = scope.copy(mode = CodingInteractionMode.PLANNING)

    private fun step(state: SessionOrganismMachine.State, vararg inputs: SessionOrganismMachine.Input) = inputs.fold(state) { current, input ->
        SessionOrganismMachine.reduce(current, input).also { assertEquals(null, it.reject, "$input was refused: ${it.reject}") }.state
    }

    private val initial = SessionOrganismMachine.initial("root")
    private val pending = step(initial, Fact.Adopt(stamp(), "project", rootSession, emptyList(), OrganismLimits()))
    private val running = step(pending, Intent.BeginRun(stamp(), "root", "root"))

    private fun create(operation: String, name: String) = Intent.Command(stamp(), scope, operation,
        OrganismCommand(OrganismAction.CREATE, name = name, task = SessionTask("Work", "root", "Result")), "fp-$operation")

    private val stages = setOf("stage", "stage-stopping", "stage-stopped")
    private fun attempt(stage: String) = SessionLegacyAttempt("plan", "run", stage, "attempt-$stage", 0, 1)
    private fun admit(id: String, stage: String) = Intent.AdmitPlanWorker(stamp(), "root",
        CodingSession(id, "project", id, 1, parentSessionId = "root"), SessionTask("Do", "root", "Done"), attempt(stage), null, stages, null, false)
    private val binding = attempt("stage")
    private val stoppingBinding = attempt("stage-stopping")
    private val stoppedBinding = attempt("stage-stopped")

    /**
     * One position holds several children, because the inputs that address a child address different ones: two ordinary
     * children for a route, a plan worker still running, one that is stopping (its retry can be reconciled) and one that
     * has stopped (its retry can be authorized).
     */
    private val child = step(running, create("make-child", "Child"), create("make-sibling", "Sibling"),
        admit("worker", "stage"), admit("worker-stopping", "stage-stopping"), admit("worker-stopped", "stage-stopped"),
        Intent.RequestUserStop(stamp(), "root", "worker-stopping", "stop-stopping", false),
        Intent.RequestUserStop(stamp(), "root", "worker-stopped", "stop-stopped", false),
        Fact.Observe(stamp(), "root", "worker-stopped", 1, SessionObservedState.STOPPED))

    private val workspace = SessionCodingWorkspace(1, "run",
        StageAttempt("attempt", "session-make-child", StageAssignment("profile", "model"), path = "/work/child", resultCommit = "commit"),
        SessionCodingWorkspacePhase.CAPTURED, resultSnapshot = "snap")
    private val result = SessionResult("result", "session-make-child", 1, "root", "Done", sourceVersion = "snap", commitSha = "commit", checks = listOf("test"))
    private val review = OrganismCommand(OrganismAction.REVIEW_RESULT, "session-make-child", resultId = "result", accepted = true,
        reason = "Verified", sourceVersion = "snap", checks = listOf("test"))
    private val resulted = step(running, create("make-child", "Child"),
        Fact.RecordWorkspace(stamp(), "root", "session-make-child", 1, workspace),
        Fact.Observe(stamp(), "root", "session-make-child", 1, SessionObservedState.COMPLETED),
        Fact.RecordResult(stamp(), "root", result))
    private val integrable = step(resulted, Intent.Command(stamp(), scope, "review", review, "fp-review"))
    private val integration = SessionIntegrationRequest("integ", "root", "root", 1, listOf("result"), listOf(listOf("test")), "/work/child", "snap")
    private val integrating = step(integrable, Intent.AdmitIntegration(stamp(), scope, integration, "fp-integ"))
    private val auxiliaryAdmission = OrganismAuxiliaryAdmission("project", "root", "root", "aux-session", 1, "run", "request",
        CodingInteractionMode.CODE, ToolRole.PLANNER, null, false)
    private val auxiliary = step(running, Intent.BeginAuxiliary(stamp(), auxiliaryAdmission))

    private val stopping = step(running, Intent.RequestUserStop(stamp(), "root", "root", "stop", false))
    private val finished = step(running, Fact.Observe(stamp(), "root", "root", 1, SessionObservedState.COMPLETED))
    private val stopped = step(stopping, Fact.Observe(stamp(), "root", "root", 1, SessionObservedState.STOPPED))
    private val unknown = step(running, Fact.Restored(stamp(), "root"))
    private val archived = step(finished, Intent.SetArchiveVisibility(stamp(), "root", "root", 1, archived = true, stillReady = true))
    private val deleted = step(finished, Intent.DeleteHistoryByUser(stamp(), "root", null))
    private val persistenceUnknown = step(running, Fact.PersistenceUnknown(stamp()))

    private val planningRunning = step(initial, Fact.Adopt(stamp(), "project", planningSession, emptyList(), OrganismLimits()),
        Intent.BeginRun(stamp(), "root", "root"))
    private val quarantined = step(planningRunning, Fact.Observe(stamp(), "root", "root", 1, SessionObservedState.FAILED),
        Intent.Command(stamp(), planningScope, "signal", OrganismCommand(OrganismAction.SIGNAL, "root", reason = "Stuck"), "fp-signal"),
        Intent.InspectSignals(stamp(), "root"))
    private val proposed = step(quarantined, Intent.ProposeImmunityInterventions(stamp(), "root"))
    private val intervention = proposed.organism!!.interventions.single().id
    private val intervening = step(proposed, Intent.AcceptImmunityIntervention(stamp(), "root", intervention, ImmunityAction.STOP, null, null, false))

    private val states = mapOf(
        SessionOrganismSpace.EMPTY to initial,
        SessionOrganismSpace.PERSISTENCE_UNKNOWN to persistenceUnknown,
        SessionOrganismSpace.DELETED to deleted,
        SessionOrganismSpace.INTERVENING to intervening,
        SessionOrganismSpace.PROPOSED to proposed,
        SessionOrganismSpace.QUARANTINED to quarantined,
        SessionOrganismSpace.UNKNOWN to unknown,
        SessionOrganismSpace.ARCHIVED to archived,
        SessionOrganismSpace.STOPPING to stopping,
        SessionOrganismSpace.FINISHED to finished,
        SessionOrganismSpace.STOPPED to stopped,
        SessionOrganismSpace.INTEGRATING to integrating,
        SessionOrganismSpace.AUXILIARY to auxiliary,
        SessionOrganismSpace.INTEGRABLE to integrable,
        SessionOrganismSpace.RESULT to resulted,
        SessionOrganismSpace.CHILD to child,
        SessionOrganismSpace.RUNNING to running,
        SessionOrganismSpace.PENDING to pending,
    )

    private fun command(action: OrganismAction, operation: String = "op-${action.name}", request: OrganismCommand) =
        Intent.Command(stamp(), scope, operation, request.copy(action = action), "fp-$operation")

    private val auxiliaryId = auxiliary.organism!!.auxiliaryRuns.keys.single()
    private val stoppingNode = child.organism!!.sessions.getValue("worker-stopping")
    private val legacyOrganism = pending.organism!!

    private val inputs: Map<InputId, SessionOrganismMachine.Input> = mapOf(
        SessionOrganismSpace.SET_ARCHIVE_VISIBILITY to Intent.SetArchiveVisibility(stamp(), "root", "root", 1, archived = true, stillReady = true),
        SessionOrganismSpace.APPLY_LIMITS to Intent.ApplyLimits(stamp(), "root", OrganismLimits(tokens = 1000, recoveryTokens = 100)),
        SessionOrganismSpace.ADMIT_INTEGRATION to Intent.AdmitIntegration(stamp(), scope, integration.copy(id = "integ-new"), "fp-integ-new"),
        SessionOrganismSpace.RENAME_BY_USER to Intent.RenameByUser(stamp(), "root", "root", "Renamed", "rename", "fp-rename"),
        SessionOrganismSpace.CHECK to Intent.Check(stamp(), scope),
        SessionOrganismSpace.AUTHORIZE_PLAN_RETRY to Intent.AuthorizePlanRetry(stamp(), "root", "worker-stopped", stoppedBinding, false),
        SessionOrganismSpace.ADMIT_PLAN_WORKER to admit("worker", "stage"),
        SessionOrganismSpace.CHANGE_ROOT_MODE to Intent.ChangeRootMode(stamp(), "root", "root", CodingInteractionMode.RESEARCH),
        SessionOrganismSpace.PREPARE_USER_TURN to Intent.PrepareUserTurn(stamp(), "root", "root", "turn"),
        SessionOrganismSpace.BEGIN_RUN to Intent.BeginRun(stamp(), "root", "root"),
        SessionOrganismSpace.REQUEST_USER_STOP to Intent.RequestUserStop(stamp(), "root", "root", "user-stop", false),
        SessionOrganismSpace.RESTORE_BY_USER to Intent.RestoreByUser(stamp(), "root", "root", "restore", rules, null),
        SessionOrganismSpace.COMMAND_CREATE to create("new-child", "New"),
        SessionOrganismSpace.COMMAND_SEND to command(OrganismAction.SEND, request = OrganismCommand(OrganismAction.SEND,
            "session-make-child", packet = SessionContextPacket("Context"))),
        SessionOrganismSpace.COMMAND_ROUTE to command(OrganismAction.ROUTE, request = OrganismCommand(OrganismAction.ROUTE,
            "session-make-sibling", source = "session-make-child", reason = "Share")),
        SessionOrganismSpace.COMMAND_REVIEW_RESULT to command(OrganismAction.REVIEW_RESULT, "review-new", review),
        SessionOrganismSpace.COMMAND_WAIT to command(OrganismAction.WAIT, request = OrganismCommand(OrganismAction.WAIT,
            dependencies = setOf("session-make-child"))),
        SessionOrganismSpace.COMMAND_STOP to command(OrganismAction.STOP, request = OrganismCommand(OrganismAction.STOP, "session-make-child")),
        SessionOrganismSpace.COMMAND_PAUSE to command(OrganismAction.PAUSE, request = OrganismCommand(OrganismAction.PAUSE, "session-make-child")),
        SessionOrganismSpace.COMMAND_QUARANTINE to command(OrganismAction.QUARANTINE, request = OrganismCommand(OrganismAction.QUARANTINE, "session-make-child")),
        SessionOrganismSpace.COMMAND_ARCHIVE to command(OrganismAction.ARCHIVE, request = OrganismCommand(OrganismAction.ARCHIVE, "session-make-child")),
        SessionOrganismSpace.COMMAND_RESTORE to command(OrganismAction.RESTORE, request = OrganismCommand(OrganismAction.RESTORE, "session-make-child", reason = "Again")),
        SessionOrganismSpace.COMMAND_RENAME to command(OrganismAction.RENAME, request = OrganismCommand(OrganismAction.RENAME, "session-make-child", name = "Renamed")),
        SessionOrganismSpace.COMMAND_SIGNAL to command(OrganismAction.SIGNAL, request = OrganismCommand(OrganismAction.SIGNAL, "root", reason = "Stuck")),
        SessionOrganismSpace.BEGIN_AUXILIARY to Intent.BeginAuxiliary(stamp(), auxiliaryAdmission),
        SessionOrganismSpace.DELETE_HISTORY_BY_USER to Intent.DeleteHistoryByUser(stamp(), "root", null),
        SessionOrganismSpace.PROPOSE_IMMUNITY_INTERVENTIONS to Intent.ProposeImmunityInterventions(stamp(), "root"),
        SessionOrganismSpace.ACCEPT_IMMUNITY_INTERVENTION to Intent.AcceptImmunityIntervention(stamp(), "root", intervention, ImmunityAction.STOP, null, null, false),
        SessionOrganismSpace.DISMISS_IMMUNITY_INTERVENTION to Intent.DismissImmunityIntervention(stamp(), "root", intervention),
        SessionOrganismSpace.INSPECT_SIGNALS to Intent.InspectSignals(stamp(), "root"),
        SessionOrganismSpace.CHECKPOINT_INTEGRATION to Fact.CheckpointIntegration(stamp(), "root",
            integrating.organism!!.integrations.getValue("integ").copy(phase = SessionIntegrationPhase.PREPARING)),
        SessionOrganismSpace.ADOPT to Fact.Adopt(stamp(), "project", rootSession, emptyList(), OrganismLimits()),
        SessionOrganismSpace.REQUEST_FAILURE_STOP to Fact.RequestFailureStop(stamp(), "root", "root", 1, "Failed"),
        SessionOrganismSpace.RECONCILE_AND_AUTHORIZE_PLAN_RETRY to Fact.ReconcileAndAuthorizePlanRetry(stamp(), "root",
            OrganismRetryRequest("project", "worker-stopping", stoppingBinding, stoppingNode.version, emptyList()),
            PlanRetryRecoveryProof(emptySet(), listOf("proof")), stoppingBinding, false),
        SessionOrganismSpace.ACCEPT_PLAN_RESULT to Fact.AcceptPlanResult(stamp(), "root", binding,
            SessionResult("plan-result", "worker", 1, "root", "Done", checks = listOf("test"), accepted = true)),
        SessionOrganismSpace.RECORD_WORKSPACE to Fact.RecordWorkspace(stamp(), "root", "root", 1,
            workspace.copy(attempt = workspace.attempt.copy(sessionId = "root"))),
        SessionOrganismSpace.RESOLVE_SESSION_QUARANTINE to Fact.ResolveSessionQuarantine(stamp(), "root", "root",
            SessionQuarantineResolution(true, false, quarantined.organism!!.pendingQuarantines("root").map { it.operationId }.toSet(), listOf("proof"))),
        SessionOrganismSpace.RECONCILE_INTERRUPTED_RUN to Fact.ReconcileInterruptedRun(stamp(), "root", "root", 1, unknown.organism!!.sessions.getValue("root").version),
        SessionOrganismSpace.FINISH_STOP to Fact.FinishStop(stamp(), "root", setOf("root")),
        SessionOrganismSpace.OBSERVE_RUNNING to Fact.Observe(stamp(), "root", "root", 1, SessionObservedState.RUNNING),
        SessionOrganismSpace.OBSERVE_SETTLED to Fact.Observe(stamp(), "root", "root", 1, SessionObservedState.COMPLETED),
        SessionOrganismSpace.OBSERVE_OTHER to Fact.Observe(stamp(), "root", "root", 1, SessionObservedState.WAITING_USER),
        SessionOrganismSpace.ACKNOWLEDGE to Fact.Acknowledge(stamp(), "root", resulted.organism!!.outbox.single().id, "root", 1, false),
        SessionOrganismSpace.CHARGE to Fact.Charge(stamp(), scope, 10),
        SessionOrganismSpace.CHARGE_AUXILIARY to Fact.ChargeAuxiliary(stamp(), "root", auxiliaryId, "source", 10),
        SessionOrganismSpace.FINISH_AUXILIARY to Fact.FinishAuxiliary(stamp(), "root", auxiliaryId, SessionObservedState.COMPLETED),
        SessionOrganismSpace.RESTORED to Fact.Restored(stamp(), "root"),
        SessionOrganismSpace.RECORD_RESULT to Fact.RecordResult(stamp(), "root", result.copy(id = "result-new")),
        SessionOrganismSpace.FINISH_IMMUNITY_INTERVENTION to Fact.FinishImmunityIntervention(stamp(), "root", intervention, null),
        SessionOrganismSpace.QUARANTINE_FACT to Fact.Quarantine(stamp(), "root", "root", 1, "quarantine", "Unknown outcome"),
        SessionOrganismSpace.LEGACY_IMPORTED to Fact.LegacyImported(stamp(), legacyOrganism),
        SessionOrganismSpace.LIMIT_POLICY_MIGRATED to Fact.LimitPolicyMigrated(stamp()),
        SessionOrganismSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown(stamp()),
    )


    init {
        // The values above address `root` under generation 1 and a CODE authority; a representative that stood elsewhere would be
        // refused for a reason the matrix does not describe.
        val planning = setOf(SessionOrganismSpace.INTERVENING, SessionOrganismSpace.PROPOSED, SessionOrganismSpace.QUARANTINED)
        states.forEach { (phase, state) ->
            val root = state.organism?.sessions?.get("root") ?: return@forEach
            if (phase != SessionOrganismSpace.DELETED) assertEquals(1L, root.generation, "${phase.name} stands under another generation")
            assertEquals(if (phase in planning) CodingInteractionMode.PLANNING else CodingInteractionMode.CODE, root.mode, "${phase.name} stands under another mode")
        }
        val holders = listOf(SessionOrganismSpace.INTEGRATING, SessionOrganismSpace.INTEGRABLE, SessionOrganismSpace.RESULT, SessionOrganismSpace.CHILD)
        holders.forEach { assertTrue("session-make-child" in states.getValue(it).organism!!.sessions, "${it.name} lacks the child the inputs address") }
        assertEquals(1, resulted.organism!!.outbox.size)
        assertEquals(setOf("diagnosis-signal"), quarantined.organism!!.pendingQuarantines("root").map { it.operationId }.toSet())
    }

    /**
     * Several inputs look a record up with `getValue`, `first` or `single`, so a session, delivery, auxiliary run or proposal
     * that does not exist escapes `reduce` as a `NoSuchElementException`, where the neighbouring guards answer with a `Reject`.
     * The harness cannot drive an input that throws, so it runs against this wrapper, which turns exactly that exception into
     * the refusal the guard clearly means. The pin below fails when the reducer is fixed, and the wrapper should then be deleted.
     */
    private object Guarded : Machine<SessionOrganismMachine.State, SessionOrganismMachine.Input, Effect> {
        override val id: MachineId get() = SessionOrganismMachine.id
        override val space get() = SessionOrganismMachine.space
        override fun step(state: SessionOrganismMachine.State, input: SessionOrganismMachine.Input): Step<SessionOrganismMachine.State, Effect> =
            try { SessionOrganismMachine.step(state, input) } catch (missing: NoSuchElementException) {
                Step(state, listOf(Effect.Reject(SessionOrganismMachine.Reject(SessionOrganismMachine.Rejection.VALIDATION, "Запись не найдена"))))
            }
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(Guarded, states, inputs)

    @Test fun aMissingRecordEscapesTheReducerInsteadOfBeingRefused() {
        val ghost = SessionCodingWorkspace(1, "run", StageAttempt("attempt", "ghost", StageAssignment("profile", "model")))
        val escapes = listOf(
            "an unknown session to run" to Intent.BeginRun(stamp(), "root", "ghost"),
            "an unknown session observed" to Fact.Observe(stamp(), "root", "ghost", 1, SessionObservedState.COMPLETED),
            "an unknown session quarantined" to Fact.Quarantine(stamp(), "root", "ghost", 1, "quarantine", "Unknown"),
            "a workspace for an unknown session" to Fact.RecordWorkspace(stamp(), "root", "ghost", 1, ghost),
            "an unknown session settled" to Fact.FinishStop(stamp(), "root", setOf("ghost")),
            "an unknown delivery acknowledged" to Fact.Acknowledge(stamp(), "root", "ghost", "root", 1, false),
            "an unknown auxiliary run charged" to Fact.ChargeAuxiliary(stamp(), "root", "ghost", "source", 10),
            "an unknown auxiliary run finished" to Fact.FinishAuxiliary(stamp(), "root", "ghost", SessionObservedState.COMPLETED),
            "an unknown proposal dismissed" to Intent.DismissImmunityIntervention(stamp(), "root", "ghost"),
            "an unknown proposal finished" to Fact.FinishImmunityIntervention(stamp(), "root", "ghost", null),
            "a result of an unknown session" to Fact.RecordResult(stamp(), "root", result.copy(sessionId = "ghost")),
            "a plan result of an unknown session" to Fact.AcceptPlanResult(stamp(), "root", binding, result.copy(sessionId = "ghost")),
        )
        escapes.forEach { (what, input) -> assertFailsWith<NoSuchElementException>(what) { SessionOrganismMachine.reduce(pending, input) } }
        // The one neighbour that does refuse: it looks its proposal up with `firstOrNull` and answers `error(...)`.
        val refused = SessionOrganismMachine.reduce(pending, Intent.AcceptImmunityIntervention(stamp(), "root", "ghost", ImmunityAction.STOP, null, null, false))
        assertEquals(SessionOrganismMachine.Rejection.VALIDATION, refused.reject?.kind)
        assertEquals(pending, refused.state)
    }

    /** `step` is a bridge, not a second reducer: it may neither drop nor invent anything `reduce` says. */
    @Test fun theBridgeCarriesEveryOutputAndTheRefusalWithoutChangingTheTransition() {
        val seen = mutableSetOf<EffectId>()
        for ((phase, state) in states) for ((name, input) in inputs) {
            val transition = try { SessionOrganismMachine.reduce(state, input) } catch (_: NoSuchElementException) { continue }
            val bridged = SessionOrganismMachine.step(state, input)
            val where = "${phase.name} × ${name.name}"
            assertEquals(transition.state, bridged.state, where)
            assertEquals(transition.outputs, bridged.effects.filterIsInstance<Effect.Emit>().map { it.output }, where)
            assertEquals(transition.reject, bridged.effects.filterIsInstance<Effect.Reject>().singleOrNull()?.reject, where)
            assertEquals(transition.outputs.size + if (transition.reject == null) 0 else 1, bridged.effects.size, where)
            bridged.effects.mapTo(seen, SessionOrganismSpace::name)
        }
        // Nothing is declared that the machine never emits, and nothing is emitted that is not declared.
        assertEquals(SessionOrganismSpace.effects.toSet(), seen)
    }

    @Test fun anUnconfirmedPersistenceRefusesEverythingButItsOwnFactAndABlankStampRefusesEvenThat() {
        val fenced = states.getValue(SessionOrganismSpace.PERSISTENCE_UNKNOWN)
        for ((name, input) in inputs) {
            if (name == SessionOrganismSpace.PERSISTENCE_UNKNOWN_FACT) continue
            val transition = SessionOrganismMachine.reduce(fenced, input)
            assertEquals(SessionOrganismMachine.Rejection.UNKNOWN, transition.reject?.kind, name.name)
        }
        // The bare stamp is the outermost guard: it outranks the fact that is otherwise accepted everywhere.
        val unstamped = SessionOrganismMachine.Stamp("", 1000)
        for ((phase, state) in states) for (input in listOf(Fact.PersistenceUnknown(unstamped), Fact.Restored(unstamped, "root"), Intent.InspectSignals(unstamped, "root"))) {
            val transition = SessionOrganismMachine.reduce(state, input)
            assertEquals(SessionOrganismMachine.Rejection.VALIDATION, transition.reject?.kind, "${phase.name} × $input")
            assertEquals(state, transition.state)
        }
    }

    /** Organisms the machine could not build in one step; an import is a fact like any other, so it is how they are made. */
    private fun node(id: String, observed: SessionObservedState = SessionObservedState.PENDING) =
        SessionNode(id, SessionKind.SESSION, id, originParentId = "root", generation = 1, observed = observed)
    private val zygote = SessionNode("root", SessionKind.ZYGOTE, "Root", generation = 1)
    private val bare = SessionOrganism("root", "project", "root", createdAt = 1, sessions = mapOf("root" to zygote))
    private fun imported(organism: SessionOrganism) = step(initial, Fact.LegacyImported(stamp(), organism))
    private fun SessionOrganism.root(change: (SessionNode) -> SessionNode) = copy(sessions = sessions + ("root" to change(sessions.getValue("root"))))
    private fun proposal(state: ImmunityInterventionState) = ImmunityIntervention("proposal", "signal", "root", 1, setOf(ImmunityAction.STOP),
        listOf("Evidence"), setOf("root"), 1, state)
    private val integrationRequest = SessionIntegrationRequest("integration", "root", "root", 1, listOf("result"), listOf(listOf("test")), "/source", "snap")
    private val auxiliaryRun = SessionAuxiliaryRun("aux", "aux-session", "root", 1, "run", "request", CodingInteractionMode.CODE)
    private val quarantineEvent = SessionAuditEvent("quarantine", "APPLICATION", QUARANTINE_ACTION, setOf("root"), "Unknown outcome", 1)

    /** One fact each, in the order [SessionOrganismSpace.label] ranks them. */
    private class Shape(val position: PhaseId, val add: (SessionOrganism) -> SessionOrganism)
    private val shapes = listOf(
        Shape(SessionOrganismSpace.DELETED) { it.copy(deletedAt = 1) },
        Shape(SessionOrganismSpace.INTERVENING) { it.copy(interventions = it.interventions + proposal(ImmunityInterventionState.ACCEPTED)) },
        Shape(SessionOrganismSpace.PROPOSED) { it.copy(interventions = it.interventions + proposal(ImmunityInterventionState.PROPOSED)) },
        Shape(SessionOrganismSpace.QUARANTINED) { it.copy(audit = it.audit + quarantineEvent) },
        Shape(SessionOrganismSpace.UNKNOWN) { it.copy(sessions = it.sessions + ("lost" to node("lost", SessionObservedState.UNKNOWN))) },
        Shape(SessionOrganismSpace.ARCHIVED) { organism -> organism.root { it.copy(archived = true) } },
        Shape(SessionOrganismSpace.STOPPING) { organism -> organism.root { it.copy(observed = SessionObservedState.STOPPING, desired = SessionDesiredState.STOP) } },
        Shape(SessionOrganismSpace.FINISHED) { organism -> organism.root { it.copy(observed = SessionObservedState.COMPLETED) } },
        Shape(SessionOrganismSpace.STOPPED) { organism -> organism.root { it.copy(observed = SessionObservedState.STOPPED, desired = SessionDesiredState.STOP) } },
        Shape(SessionOrganismSpace.INTEGRATING) { it.copy(integrations = it.integrations + ("integration" to SessionIntegration(integrationRequest))) },
        Shape(SessionOrganismSpace.AUXILIARY) { it.copy(auxiliaryRuns = it.auxiliaryRuns + ("aux" to auxiliaryRun)) },
        Shape(SessionOrganismSpace.INTEGRABLE) { it.copy(results = it.results + SessionResult("accepted", "root", 1, "root", "Done", accepted = true)) },
        Shape(SessionOrganismSpace.RESULT) { it.copy(results = it.results + SessionResult("waiting", "root", 1, "root", "Done")) },
        Shape(SessionOrganismSpace.CHILD) { it.copy(sessions = it.sessions + ("child" to node("child"))) },
        Shape(SessionOrganismSpace.RUNNING) { organism -> organism.root { it.copy(observed = SessionObservedState.RUNNING) } },
    )

    /**
     * The harness drives representatives that each carry one fact, so it cannot see which fact wins when an organism carries
     * two. Swapping any two adjacent clauses of `label` would leave it green, and the order is the part that says an unknown
     * outcome outranks a finished root and that records are named only while the root can still take work.
     */
    @Test fun theMostPressingFactNamesThePositionWhateverElseTheOrganismHolds() {
        assertEquals(SessionOrganismSpace.PENDING, SessionOrganismSpace.label(imported(bare)))
        shapes.forEach { assertEquals(it.position, SessionOrganismSpace.label(imported(it.add(bare))), "${it.position.name} alone") }
        // Three of these describe the same field of the root, so an organism cannot carry two of them.
        val exclusive = setOf(SessionOrganismSpace.STOPPING, SessionOrganismSpace.FINISHED, SessionOrganismSpace.STOPPED, SessionOrganismSpace.RUNNING)
        for ((index, higher) in shapes.withIndex()) for (lower in shapes.drop(index + 1)) {
            if (higher.position in exclusive && lower.position in exclusive) continue
            assertEquals(higher.position, SessionOrganismSpace.label(imported(lower.add(higher.add(bare)))), "${higher.position.name} over ${lower.position.name}")
            assertEquals(higher.position, SessionOrganismSpace.label(imported(higher.add(lower.add(bare)))), "${higher.position.name} over ${lower.position.name}, built the other way round")
        }
        // What a fact is *not*: a settled record is no longer pending work, and neither is an intervention that ended.
        val neither = mapOf(
            "an auxiliary run that completed" to bare.copy(auxiliaryRuns = mapOf("aux" to auxiliaryRun.copy(observed = SessionObservedState.COMPLETED))),
            "an intervention that completed" to bare.copy(interventions = listOf(proposal(ImmunityInterventionState.COMPLETED))),
            "an intervention that was rejected" to bare.copy(interventions = listOf(proposal(ImmunityInterventionState.REJECTED))),
            "an integration that verified" to bare.copy(integrations = mapOf("integration" to SessionIntegration(integrationRequest, SessionIntegrationPhase.VERIFIED))),
            "an integration that conflicted" to bare.copy(integrations = mapOf("integration" to SessionIntegration(integrationRequest, SessionIntegrationPhase.CONFLICT))),
            "an integration that was blocked" to bare.copy(integrations = mapOf("integration" to SessionIntegration(integrationRequest, SessionIntegrationPhase.BLOCKED))),
        )
        neither.forEach { (what, organism) -> assertEquals(SessionOrganismSpace.PENDING, SessionOrganismSpace.label(imported(organism)), what) }
        // Each phase of an integration between its intent and its verdict is `integrating`, and so is an intervention whose outcome is unknown.
        listOf(SessionIntegrationPhase.INTENT, SessionIntegrationPhase.PREPARING, SessionIntegrationPhase.MERGING, SessionIntegrationPhase.VERIFYING).forEach {
            assertEquals(SessionOrganismSpace.INTEGRATING, SessionOrganismSpace.label(imported(bare.copy(integrations = mapOf("integration" to SessionIntegration(integrationRequest, it))))), it.name)
        }
        assertEquals(SessionOrganismSpace.INTERVENING, SessionOrganismSpace.label(imported(bare.copy(interventions = listOf(proposal(ImmunityInterventionState.UNKNOWN))))))
        // A quarantine or an unknown run anywhere in the organism outranks its root, not only one on the root itself.
        val onChild = bare.copy(sessions = bare.sessions + ("child" to node("child")), audit = listOf(quarantineEvent.copy(affected = setOf("child"))))
        assertEquals(SessionOrganismSpace.QUARANTINED, SessionOrganismSpace.label(imported(onChild)))
        assertTrue(SessionOrganismSpace.unknown(imported(onChild)))
        // A user stop of the whole organism is the same fence as a deletion, though only a legacy record carries one without the other.
        assertEquals(SessionOrganismSpace.DELETED, SessionOrganismSpace.label(imported(bare.copy(stoppedByUser = true))))
        // The fences outrank everything: unconfirmed persistence even a deleted organism, and an organism nobody has adopted yet.
        assertEquals(SessionOrganismSpace.PERSISTENCE_UNKNOWN, SessionOrganismSpace.label(step(deleted, Fact.PersistenceUnknown(stamp()))))
        assertEquals(SessionOrganismSpace.PERSISTENCE_UNKNOWN, SessionOrganismSpace.label(step(initial, Fact.PersistenceUnknown(stamp()))))
        assertEquals(SessionOrganismSpace.EMPTY, SessionOrganismSpace.label(initial))
        // A running root that holds a child is `child`, not `running`: the record refines a root that accepts work.
        assertEquals(SessionOrganismSpace.CHILD, SessionOrganismSpace.label(child))
    }

    /**
     * Each clause of `unknown` is a different kind of outcome that was requested and never confirmed, and none of them is implied by
     * another, so each needs an organism that carries that clause and nothing else.
     */
    @Test fun everyKindOfUnconfirmedOutcomeIsUnknownAndNothingElseIs() {
        val unconfirmed = mapOf(
            "a session whose run is unknown" to bare.copy(sessions = bare.sessions + ("lost" to node("lost", SessionObservedState.UNKNOWN))),
            "a child's workspace whose outcome is unknown" to bare.copy(sessions = bare.sessions + ("lost" to node("lost").copy(workspace =
                SessionCodingWorkspace(1, "run", StageAttempt("attempt", "lost", StageAssignment("profile", "model")), SessionCodingWorkspacePhase.UNKNOWN)))),
            "a quarantine that touches only a child" to bare.copy(sessions = bare.sessions + ("child" to node("child")), audit = listOf(quarantineEvent.copy(affected = setOf("child")))),
            "a workspace whose outcome is unknown" to bare.root { it.copy(workspace = SessionCodingWorkspace(1, "run",
                StageAttempt("attempt", "root", StageAssignment("profile", "model")), SessionCodingWorkspacePhase.UNKNOWN)) },
            "an operation whose outcome is unknown" to bare.copy(operations = mapOf("op" to OrganismOperation("op", "fp", "root", SessionOperationState.UNKNOWN))),
            "an integration whose outcome is unknown" to bare.copy(integrations = mapOf("integration" to
                SessionIntegration(integrationRequest, SessionIntegrationPhase.UNKNOWN))),
            "an auxiliary run whose outcome is unknown" to bare.copy(auxiliaryRuns = mapOf("aux" to auxiliaryRun.copy(observed = SessionObservedState.UNKNOWN))),
            "an intervention whose outcome is unknown" to bare.copy(interventions = listOf(proposal(ImmunityInterventionState.UNKNOWN))),
            "a quarantine nobody has confirmed" to bare.copy(audit = listOf(quarantineEvent)),
        )
        unconfirmed.forEach { (what, organism) -> assertTrue(SessionOrganismSpace.unknown(imported(organism)), what) }
        assertTrue(SessionOrganismSpace.unknown(step(initial, Fact.PersistenceUnknown(stamp()))), "unconfirmed persistence")

        val resolution = SessionAuditEvent(quarantineResolutionId("root", "quarantine"), "APPLICATION", QUARANTINE_RESOLVED_ACTION, setOf("root"), "Proven", 1)
        val confirmed = mapOf(
            "an organism nobody has touched" to bare,
            "an operation that succeeded" to bare.copy(operations = mapOf("op" to OrganismOperation("op", "fp", "root", SessionOperationState.SUCCEEDED))),
            "an operation that was accepted" to bare.copy(operations = mapOf("op" to OrganismOperation("op", "fp", "root", SessionOperationState.ACCEPTED))),
            "an integration still in flight" to bare.copy(integrations = mapOf("integration" to SessionIntegration(integrationRequest))),
            "an integration that verified" to bare.copy(integrations = mapOf("integration" to SessionIntegration(integrationRequest, SessionIntegrationPhase.VERIFIED))),
            "an auxiliary run that is running" to bare.copy(auxiliaryRuns = mapOf("aux" to auxiliaryRun)),
            "an intervention that was accepted" to bare.copy(interventions = listOf(proposal(ImmunityInterventionState.ACCEPTED))),
            "an intervention that completed" to bare.copy(interventions = listOf(proposal(ImmunityInterventionState.COMPLETED))),
            "a quarantine that was resolved" to bare.copy(audit = listOf(quarantineEvent, resolution)),
            "a workspace that was captured" to bare.root { it.copy(workspace = SessionCodingWorkspace(1, "run",
                StageAttempt("attempt", "root", StageAssignment("profile", "model")), SessionCodingWorkspacePhase.CAPTURED)) },
            "a root that is stopping" to bare.root { it.copy(observed = SessionObservedState.STOPPING) },
            "a deleted organism" to bare.copy(deletedAt = 1),
        )
        confirmed.forEach { (what, organism) -> assertFalse(SessionOrganismSpace.unknown(imported(organism)), what) }
        assertFalse(SessionOrganismSpace.unknown(initial))
        assertFalse(SessionOrganismSpace.unknown(running))
        assertFalse(SessionOrganismSpace.unknown(stopped))
    }

    /** Ties and blind spots the documentation admits to; pinned so it cannot drift from what `label` and `unknown` do. */
    @Test fun whatThePositionDoesNotNameIsWhatTheDocumentationSays() {
        // Unknown outcomes that no position is named for: the organism is labelled by its root, and unknown() still reports them.
        val operation = imported(bare.copy(operations = mapOf("op" to OrganismOperation("op", "fp", "root", SessionOperationState.UNKNOWN))))
        assertEquals(SessionOrganismSpace.PENDING, SessionOrganismSpace.label(operation))
        assertTrue(SessionOrganismSpace.unknown(operation))
        val auxiliaryLost = imported(bare.copy(auxiliaryRuns = mapOf("aux" to auxiliaryRun.copy(observed = SessionObservedState.UNKNOWN))))
        assertEquals(SessionOrganismSpace.AUXILIARY, SessionOrganismSpace.label(auxiliaryLost))
        assertTrue(SessionOrganismSpace.unknown(auxiliaryLost))
        val integrationLost = imported(bare.copy(integrations = mapOf("integration" to SessionIntegration(integrationRequest, SessionIntegrationPhase.UNKNOWN))))
        assertEquals(SessionOrganismSpace.PENDING, SessionOrganismSpace.label(integrationLost))
        assertTrue(SessionOrganismSpace.unknown(integrationLost))
        // A root that was running and is restored is unknown; one that had an integration in flight is a quarantine instead.
        assertEquals(SessionOrganismSpace.UNKNOWN, SessionOrganismSpace.label(unknown))
        assertEquals(SessionOrganismSpace.QUARANTINED, SessionOrganismSpace.label(step(integrating, Fact.Restored(stamp(), "root"))))
        // The three intervention positions stand on a PLANNING root. A CODE root with a quarantine is `quarantined` as well, and takes a
        // signal that the row, read off the PLANNING representative, refuses.
        val codeQuarantine = step(running, Fact.Quarantine(stamp(), "root", "root", 1, "quarantine", "Unknown outcome"))
        assertEquals(SessionOrganismSpace.QUARANTINED, SessionOrganismSpace.label(codeQuarantine))
        assertEquals(null, SessionOrganismMachine.reduce(codeQuarantine, inputs.getValue(SessionOrganismSpace.COMMAND_SIGNAL)).reject)
        assertFalse(SessionOrganismSpace.QUARANTINED in SessionOrganismSpace.accepts.getValue(SessionOrganismSpace.COMMAND_SIGNAL))
        // A limit the representatives do not set refuses what the matrix accepts: a session cap of one refuses a second child.
        val capped = step(initial, SessionOrganismMachine.Fact.Adopt(stamp(), "project", rootSession, emptyList(), OrganismLimits(activeSessions = 1)))
        val cappedRunning = step(capped, Intent.BeginRun(stamp(), "root", "root"))
        assertEquals(SessionOrganismSpace.RUNNING, SessionOrganismSpace.label(cappedRunning))
        assertEquals(SessionOrganismMachine.Rejection.VALIDATION,
            SessionOrganismMachine.reduce(cappedRunning, Intent.BeginAuxiliary(stamp(), auxiliaryAdmission)).reject?.kind)
        assertTrue(SessionOrganismSpace.RUNNING in SessionOrganismSpace.accepts.getValue(SessionOrganismSpace.BEGIN_AUXILIARY))
    }

    /** What a report of the current generation does to a session, which the name of `Observe` does not say. */
    @Test fun aReportReplacesTheStateOfARunThatWasUnknownOnlyWhileTheSessionIsStillMeantToRun() {
        fun observed(state: SessionOrganismMachine.State, report: SessionObservedState) =
            step(state, Fact.Observe(stamp(), "root", "root", 1, report)).organism!!.sessions.getValue("root").observed
        // Still meant to run: a completion, or any other report, replaces an unknown state without a reconciliation.
        assertEquals(SessionObservedState.COMPLETED, observed(unknown, SessionObservedState.COMPLETED))
        assertEquals(SessionObservedState.WAITING_USER, observed(unknown, SessionObservedState.WAITING_USER))
        // Not meant to run any more: a completion is recorded as a stop, and never clears an unknown run.
        assertEquals(SessionObservedState.STOPPED, observed(stopping, SessionObservedState.COMPLETED))
        val quarantine = step(running, Fact.Quarantine(stamp(), "root", "root", 1, "quarantine", "Unknown outcome"))
        assertEquals(SessionObservedState.UNKNOWN, observed(quarantine, SessionObservedState.COMPLETED))
    }

    /** `AuthorizePlanRetry` is accepted in every position, so its row cannot say where a retry is actually granted. */
    @Test fun onlyAWorkerThatHasStoppedIsGrantedARetry() {
        val authorize = inputs.getValue(SessionOrganismSpace.AUTHORIZE_PLAN_RETRY)
        val granted = SessionOrganismMachine.reduce(child, authorize).outputs.single() as SessionOrganismMachine.Output.RetryAuthorized
        assertNotNull(granted.authorization)
        val nothing = SessionOrganismMachine.reduce(running, authorize).outputs.single() as SessionOrganismMachine.Output.RetryAuthorized
        assertNull(nothing.authorization)
    }

    /** A root waiting on the user is named `running`, so the reducer must treat it like one. */
    @Test fun aRootWaitingOnTheUserAnswersEveryRepresentativeLikeARunningOne() {
        val waiting = step(running, Fact.Observe(stamp(), "root", "root", 1, SessionObservedState.WAITING_USER))
        assertEquals(SessionOrganismSpace.RUNNING, SessionOrganismSpace.label(waiting))
        for ((name, input) in inputs) {
            fun refused(state: SessionOrganismMachine.State) = try { SessionOrganismMachine.reduce(state, input).reject != null } catch (_: NoSuchElementException) { true }
            assertEquals(refused(running), refused(waiting), name.name)
        }
    }

    /** Adopting the shared contract adds a supertype and a nested type; it must not touch the journaled form of an input. */
    @Test fun theSerializedFormOfAnInputIsUnchanged() {
        val json = Json
        val persistence = Fact.PersistenceUnknown(SessionOrganismMachine.Stamp("input", 5))
        assertEquals("""{"type":"io.aequicor.magicpaper.domain.SessionOrganismMachine.Fact.PersistenceUnknown","stamp":{"id":"input","at":5}}""",
            json.encodeToString(SessionOrganismMachine.Input.serializer(), persistence))
        val run = Intent.BeginRun(SessionOrganismMachine.Stamp("input", 5), "root", "session")
        assertEquals("""{"type":"io.aequicor.magicpaper.domain.SessionOrganismMachine.Intent.BeginRun","stamp":{"id":"input","at":5},"id":"root","sessionId":"session"}""",
            json.encodeToString(SessionOrganismMachine.Input.serializer(), run))
        assertEquals(run, json.decodeFromString(SessionOrganismMachine.Input.serializer(), json.encodeToString(SessionOrganismMachine.Input.serializer(), run)))
    }
}
