package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.ComputerMachine.Fact
import io.aequicor.magicpaper.domain.ComputerMachine.Intent
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The representatives of [ComputerSpace], kept here rather than in the api so a shipped binary carries
 * no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce. The lease id embeds the generation
 * it was issued at, so every position that holds a lease is reached through the same count of
 * revocations — one restore and one configuration that changes the policy — and the one lease and the
 * one policy reference below fit all of them.
 */
class ComputerSpaceTest {
    private val request = ComputerMachine.Request("session", "req")
    private fun step(state: ComputerMachine.State, input: ComputerMachine.Input) = ComputerMachine.reduce(state, input).state
    private fun call(id: String, action: ComputerMachine.Action, lease: ComputerLease, bound: ComputerMachine.Request?,
        reference: String?, at: Long) = Intent.Execute(ComputerMachine.Invocation(lease, bound, id, action, "fingerprint", reference, at))

    private val unrestored = ComputerMachine.initial()
    private val restored = step(unrestored, Fact.Restored("inc"))
    private val confirmedOff = step(restored, Intent.Configure(ComputerAccess.OFF, ComputerAccess.OFF))
    private val confirmedAllowed = step(restored, Intent.Configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL))
    private val unconfirmedAllowed = step(confirmedAllowed, Fact.Restored("inc-b"))
    private val policy = checkNotNull(ComputerMachine.policyRef(confirmedAllowed))
    private val permissionPending = step(confirmedAllowed, Intent.Enable("session", ComputerAccess.CONTROL, policy))
    private val lease = checkNotNull(permissionPending.permission).lease
    private val granted = step(permissionPending, Fact.PermissionsChecked(lease, granted = true))
    private val activeBlind = step(confirmedAllowed, Intent.Begin(request))
    private val observing = step(activeBlind, call("obs", ComputerMachine.Action.SCREENSHOT, lease, request, null, 100))
    private val activeObserved = step(observing, Fact.Returned(lease, "obs", ComputerMachine.Reference("ref", ComputerMachine.Tool.DESKTOP, 100)))
    private val pending = step(activeObserved, call("act", ComputerMachine.Action.CLICK, lease, request, "ref", 200))
    private val activeUnverified = step(pending, Fact.Failed(lease, "act", unknown = true))

    init {
        // One lease and one policy reference are used for every input below; if the builders drift
        // apart the harness would report a refusal it cannot explain, so say so here.
        assertEquals(lease, checkNotNull(activeBlind.grant).lease, "a request and a manual enable must be issued the same lease")
        assertEquals(policy, ComputerMachine.policyRef(confirmedOff), "the two confirmed policies must share one reference")
    }

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        ComputerMachine,
        states = mapOf(
            ComputerSpace.UNRESTORED to unrestored,
            ComputerSpace.RESOURCE_FAILURE to step(confirmedAllowed, Fact.ReleaseFailed),
            ComputerSpace.PERSISTENCE_UNKNOWN to step(confirmedAllowed, Fact.PersistenceUnknown),
            ComputerSpace.IDLE_UNCONFIRMED_OFF to restored,
            ComputerSpace.IDLE_UNCONFIRMED_ALLOWED to unconfirmedAllowed,
            ComputerSpace.IDLE_CONFIRMED_OFF to confirmedOff,
            ComputerSpace.IDLE_CONFIRMED_ALLOWED to confirmedAllowed,
            ComputerSpace.PERMISSION_PENDING to permissionPending,
            ComputerSpace.GRANTED to granted,
            ComputerSpace.ACTIVE_BLIND to activeBlind,
            ComputerSpace.ACTIVE_OBSERVED to activeObserved,
            // A click that may have reached the screen: nothing may act again until the screen is observed.
            ComputerSpace.ACTIVE_UNVERIFIED to activeUnverified,
            ComputerSpace.PENDING to pending,
        ),
        inputs = mapOf(
            ComputerSpace.CONFIGURE_ALLOW to Intent.Configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL),
            ComputerSpace.CONFIGURE_OFF to Intent.Configure(ComputerAccess.OFF, ComputerAccess.OFF),
            ComputerSpace.SUSPEND_POLICY to Intent.SuspendPolicy,
            ComputerSpace.ENABLE to Intent.Enable("session", ComputerAccess.CONTROL, policy),
            ComputerSpace.BEGIN to Intent.Begin(request),
            ComputerSpace.REVOKE to Intent.Revoke(),
            ComputerSpace.EXECUTE_OBSERVATION to call("probe", ComputerMachine.Action.SCREENSHOT, lease, request, null, 150),
            ComputerSpace.EXECUTE_PREVIEW to call("probe", ComputerMachine.Action.SCREENSHOT, lease, null, null, 150),
            ComputerSpace.EXECUTE_MUTATION to call("act", ComputerMachine.Action.CLICK, lease, request, "ref", 200),
            ComputerSpace.LEGACY_CONFIGURE to ComputerMachine.LegacyInput.Configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL),
            ComputerSpace.LEGACY_ENABLE to ComputerMachine.LegacyInput.Enable("session", ComputerAccess.CONTROL),
            ComputerSpace.PERMISSIONS_CHECKED to Fact.PermissionsChecked(lease, granted = true),
            ComputerSpace.RETURNED_PLAIN to Fact.Returned(lease, "act"),
            ComputerSpace.RETURNED_OBSERVED to Fact.Returned(lease, "act", ComputerMachine.Reference("ref2", ComputerMachine.Tool.DESKTOP, 300)),
            ComputerSpace.FAILED_KNOWN to Fact.Failed(lease, "act", unknown = false),
            ComputerSpace.FAILED_UNKNOWN to Fact.Failed(lease, "act", unknown = true),
            ComputerSpace.RESTORED to Fact.Restored("fresh"),
            ComputerSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
            ComputerSpace.RELEASE_FAILED to Fact.ReleaseFailed,
        ),
    )
}
