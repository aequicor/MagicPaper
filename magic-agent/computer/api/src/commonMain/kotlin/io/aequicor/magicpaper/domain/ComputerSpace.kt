package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [ComputerMachine], declared so it can be read without running anything. It is
 * the live reducer's space: [ComputerMachine.replay] restores accepted history and is outside it.
 *
 * The state has no phase. A position is read from four things in the order the reducer gates them.
 * Two flags fence everything but configuration and revocation — `persistenceUnknown` and
 * `resourceFailure` — and so does a blank `incarnation` (no owner has been restored yet). Below
 * those, a pending permission check, then a pending call, then a grant, and only then the idle
 * positions, which split by two facts that decide different inputs: whether the policy was
 * confirmed since the owner was restored (`Enable` needs it) and whether it allows any access at all
 * (`Begin` needs that). A grant without a request is `granted`, a manual preview; with one it is
 * `active`, split by what the last observation left behind — nothing, a fresh reference (a mutation
 * needs one), or an unresolved mutation that demands a new observation first.
 *
 * What the declaration cannot express, and leaves to `ComputerMachineTest`: an invocation whose lease,
 * request or call id is not the current one, a reference that is stale or older than its call, the
 * thirty-second reference lifetime, and a policy reference that is not the current one. A grant
 * carries the access of each tool separately; the representatives ask for the desktop. `active-observed`
 * means a desktop reference is held. `unknown(state)` also reports an unknown call left behind by a
 * revocation, which no position names: it is idle again, with the unknown recorded in `calls`.
 */
object ComputerSpace : StateSpace<ComputerMachine.State, ComputerMachine.Input, ComputerMachine.Effect> {
    val UNRESTORED = PhaseId("unrestored")
    val RESOURCE_FAILURE = PhaseId("resource-failure")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")
    val IDLE_UNCONFIRMED_OFF = PhaseId("idle-unconfirmed-off")
    val IDLE_UNCONFIRMED_ALLOWED = PhaseId("idle-unconfirmed-allowed")
    val IDLE_CONFIRMED_OFF = PhaseId("idle-confirmed-off")
    val IDLE_CONFIRMED_ALLOWED = PhaseId("idle-confirmed-allowed")
    val PERMISSION_PENDING = PhaseId("permission-pending")
    val GRANTED = PhaseId("granted")
    val ACTIVE_BLIND = PhaseId("active-blind")
    val ACTIVE_OBSERVED = PhaseId("active-observed")
    val ACTIVE_UNVERIFIED = PhaseId("active-unverified")
    val PENDING = PhaseId("pending")

    val CONFIGURE_ALLOW = InputId("ConfigureAllow")
    val CONFIGURE_OFF = InputId("ConfigureOff")
    val SUSPEND_POLICY = InputId("SuspendPolicy")
    val ENABLE = InputId("Enable")
    val BEGIN = InputId("Begin")
    val REVOKE = InputId("Revoke")
    val EXECUTE_OBSERVATION = InputId("ExecuteObservation")
    val EXECUTE_PREVIEW = InputId("ExecutePreview")
    val EXECUTE_MUTATION = InputId("ExecuteMutation")
    val LEGACY_CONFIGURE = InputId("LegacyConfigure")
    val LEGACY_ENABLE = InputId("LegacyEnable")
    val PERMISSIONS_CHECKED = InputId("PermissionsChecked")
    val RETURNED_PLAIN = InputId("ReturnedPlain")
    val RETURNED_OBSERVED = InputId("ReturnedObserved")
    val FAILED_KNOWN = InputId("FailedKnown")
    val FAILED_UNKNOWN = InputId("FailedUnknown")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")
    val RELEASE_FAILED = InputId("ReleaseFailed")

    override val phases = listOf(
        UNRESTORED, RESOURCE_FAILURE, PERSISTENCE_UNKNOWN,
        IDLE_UNCONFIRMED_OFF, IDLE_UNCONFIRMED_ALLOWED, IDLE_CONFIRMED_OFF, IDLE_CONFIRMED_ALLOWED,
        PERMISSION_PENDING, GRANTED, ACTIVE_BLIND, ACTIVE_OBSERVED, ACTIVE_UNVERIFIED, PENDING,
    )

    override val inputs = listOf(
        InputSpec(CONFIGURE_ALLOW, Branch.INTENT),
        InputSpec(CONFIGURE_OFF, Branch.INTENT),
        InputSpec(SUSPEND_POLICY, Branch.INTENT),
        InputSpec(ENABLE, Branch.INTENT),
        InputSpec(BEGIN, Branch.INTENT),
        InputSpec(REVOKE, Branch.INTENT),
        InputSpec(EXECUTE_OBSERVATION, Branch.INTENT),
        InputSpec(EXECUTE_PREVIEW, Branch.INTENT),
        InputSpec(EXECUTE_MUTATION, Branch.INTENT),
        InputSpec(LEGACY_CONFIGURE, Branch.INTENT),
        InputSpec(LEGACY_ENABLE, Branch.INTENT),
        InputSpec(PERMISSIONS_CHECKED, Branch.FACT),
        InputSpec(RETURNED_PLAIN, Branch.FACT),
        InputSpec(RETURNED_OBSERVED, Branch.FACT),
        InputSpec(FAILED_KNOWN, Branch.FACT),
        InputSpec(FAILED_UNKNOWN, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
        InputSpec(RELEASE_FAILED, Branch.FACT),
    )

    override val effects = listOf(EffectId("CheckPermissions"), EffectId("Invoke"), EffectId("Release"), EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`. Configuration, suspension, revocation and the
    // three facts about the owner are judged before any gate and are accepted everywhere; the two
    // legacy inputs are accepted nowhere, because the live reducer only replays them. `Begin` is
    // accepted from `active` as a repeat of the same request, which changes nothing.
    override val accepts = acceptance(phases, inputs, listOf(
        //                                Ca Co Sp En Be Re Eo Ep Em Lc Le Pc Rp Ro Fk Fu Rs Pu Rf
        /* unrestored                 */ "1110010000000000111",
        /* resource-failure           */ "1110010000000000111",
        /* persistence-unknown        */ "1110010000000000111",
        /* idle-unconfirmed-off       */ "1110010000000000111",
        /* idle-unconfirmed-allowed   */ "1110110000000000111",
        /* idle-confirmed-off         */ "1111010000000000111",
        /* idle-confirmed-allowed     */ "1111110000000000111",
        /* permission-pending         */ "1110010000010000111",
        /* granted                    */ "1111110100000000111",
        /* active-blind               */ "1110111000000000111",
        /* active-observed            */ "1110111010000000111",
        /* active-unverified          */ "1110111000000000111",
        /* pending                    */ "1110010000001111111",
    ))

    override fun label(state: ComputerMachine.State): PhaseId {
        val grant = state.grant
        return when {
            // The same order as the reducer's gates: a flag fences before a missing owner does.
            state.persistenceUnknown -> PERSISTENCE_UNKNOWN
            state.resourceFailure -> RESOURCE_FAILURE
            state.incarnation.isBlank() -> UNRESTORED
            state.permission != null -> PERMISSION_PENDING
            state.pending != null -> PENDING
            grant != null -> when {
                grant.request == null -> GRANTED
                state.observationRequired.isNotEmpty() -> ACTIVE_UNVERIFIED
                ComputerMachine.Tool.DESKTOP in state.references -> ACTIVE_OBSERVED
                else -> ACTIVE_BLIND
            }
            else -> {
                val allowed = state.desktopPolicy != ComputerAccess.OFF || state.applicationPolicy != ComputerAccess.OFF
                when {
                    state.policyConfirmed -> if (allowed) IDLE_CONFIRMED_ALLOWED else IDLE_CONFIRMED_OFF
                    else -> if (allowed) IDLE_UNCONFIRMED_ALLOWED else IDLE_UNCONFIRMED_OFF
                }
            }
        }
    }

    override fun name(input: ComputerMachine.Input): InputId = when (input) {
        // One intent, two inputs: turning everything off cannot grant anything, allowing access can.
        is ComputerMachine.Intent.Configure ->
            if (input.desktop == ComputerAccess.OFF && input.application == ComputerAccess.OFF) CONFIGURE_OFF else CONFIGURE_ALLOW
        ComputerMachine.Intent.SuspendPolicy -> SUSPEND_POLICY
        is ComputerMachine.Intent.Enable -> ENABLE
        is ComputerMachine.Intent.Begin -> BEGIN
        is ComputerMachine.Intent.Revoke -> REVOKE
        // Three kinds of call: a mutation needs a fresh reference, an observation bound to a request
        // needs an active one, and a preview — no request — is what a manual grant may run.
        is ComputerMachine.Intent.Execute -> when {
            input.invocation.action.mutating -> EXECUTE_MUTATION
            input.invocation.request == null -> EXECUTE_PREVIEW
            else -> EXECUTE_OBSERVATION
        }
        is ComputerMachine.LegacyInput.Configure -> LEGACY_CONFIGURE
        is ComputerMachine.LegacyInput.Enable -> LEGACY_ENABLE
        is ComputerMachine.Fact.PermissionsChecked -> PERMISSIONS_CHECKED
        // A result that carries a reference refreshes what a mutation may act on.
        is ComputerMachine.Fact.Returned -> if (input.reference != null) RETURNED_OBSERVED else RETURNED_PLAIN
        is ComputerMachine.Fact.Failed -> if (input.unknown) FAILED_UNKNOWN else FAILED_KNOWN
        is ComputerMachine.Fact.Restored -> RESTORED
        ComputerMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
        ComputerMachine.Fact.ReleaseFailed -> RELEASE_FAILED
    }

    override fun name(effect: ComputerMachine.Effect): EffectId = when (effect) {
        is ComputerMachine.Effect.CheckPermissions -> EffectId("CheckPermissions")
        is ComputerMachine.Effect.Invoke -> EffectId("Invoke")
        is ComputerMachine.Effect.Release -> EffectId("Release")
        is ComputerMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: ComputerMachine.State) = state.persistenceUnknown || state.resourceFailure ||
        state.observationRequired.isNotEmpty() || state.calls.values.any { it.outcome == ComputerMachine.Outcome.UNKNOWN }

    override fun rejected(effect: ComputerMachine.Effect) = effect is ComputerMachine.Effect.Reject
}
