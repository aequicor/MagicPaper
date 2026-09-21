package io.aequicor.magicpaper.domain

import kotlin.test.*

class ComputerMachineTest {
    private fun initial() = ComputerMachine.reduce(ComputerMachine.initial(), ComputerMachine.Fact.Restored("test-owner")).state
    private val request = ComputerMachine.Request("session", "run")
    private val configure = ComputerMachine.Intent.Configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
    private fun manualReady() = ComputerMachine.reduce(initial(), ComputerMachine.Intent.Configure(ComputerAccess.OFF, ComputerAccess.OFF)).state
    private fun ready() = ComputerMachine.reduce(ComputerMachine.reduce(initial(), configure).state,
        ComputerMachine.Intent.Begin(request)).state
    private fun invoke(state: ComputerMachine.State, action: ComputerMachine.Action, callId: String = "call", reference: String? = null,
        atNanos: Long = 1) = ComputerMachine.Intent.Execute(ComputerMachine.Invocation(checkNotNull(state.grant).lease,
            state.grant.request, callId, action, "fingerprint", reference, atNanos))
    private fun observed(tool: ComputerMachine.Tool = ComputerMachine.Tool.DESKTOP): ComputerMachine.State {
        val state = ready()
        val started = ComputerMachine.reduce(state, invoke(state, if (tool == ComputerMachine.Tool.DESKTOP)
            ComputerMachine.Action.SCREENSHOT else ComputerMachine.Action.INSPECT)).state
        return ComputerMachine.reduce(started, ComputerMachine.Fact.Returned(checkNotNull(state.grant).lease, "call",
            ComputerMachine.Reference("reference", tool, 2))).state
    }
    private fun reject(state: ComputerMachine.State, input: ComputerMachine.Input) {
        val next = ComputerMachine.reduce(state, input)
        assertEquals(state, next.state)
        assertIs<ComputerMachine.Effect.Reject>(next.effects.single())
    }

    @Test fun configurationCannotGrantAuthorityAndBeginRequiresAnExplicitNewRequest() {
        val configured = ComputerMachine.reduce(initial(), configure)
        assertNull(configured.state.grant)
        assertTrue(configured.effects.isEmpty())
        val started = ComputerMachine.reduce(configured.state, ComputerMachine.Intent.Begin(request))
        assertEquals(request, started.state.grant?.request)
        assertTrue(started.effects.isEmpty())
        val closed = ComputerMachine.reduce(started.state, ComputerMachine.Intent.Revoke()).state
        reject(closed, ComputerMachine.Intent.Begin(request))
        assertNotNull(ComputerMachine.reduce(closed, ComputerMachine.Intent.Begin(request.copy(id = "new-run"))).state.grant)
    }

    @Test fun manualEnableCanPreviewAndMustBeBoundBeforeNativeInput() {
        val current = manualReady()
        val enabling = ComputerMachine.reduce(current, ComputerMachine.Intent.Enable("session", ComputerAccess.CONTROL, checkNotNull(ComputerMachine.policyRef(current))))
        val check = assertIs<ComputerMachine.Effect.CheckPermissions>(enabling.effects.single())
        val granted = ComputerMachine.reduce(enabling.state, ComputerMachine.Fact.PermissionsChecked(check.lease, true)).state
        assertNull(granted.grant?.request)
        assertIs<ComputerMachine.Effect.Invoke>(ComputerMachine.reduce(granted, invoke(granted, ComputerMachine.Action.SCREENSHOT)).effects.single())
        reject(granted, invoke(granted, ComputerMachine.Action.CLICK))
        val bound = ComputerMachine.reduce(granted, ComputerMachine.Intent.Begin(request)).state
        assertEquals(check.lease, bound.grant?.lease)
        assertEquals(request, bound.grant?.request)
    }

    @Test fun stalePermissionCallbackCannotReenableRevokedAuthority() {
        val current = manualReady()
        val enabling = ComputerMachine.reduce(current, ComputerMachine.Intent.Enable("session", ComputerAccess.CONTROL, checkNotNull(ComputerMachine.policyRef(current))))
        val lease = assertIs<ComputerMachine.Effect.CheckPermissions>(enabling.effects.single()).lease
        val revoked = ComputerMachine.reduce(enabling.state, ComputerMachine.Intent.Revoke(lease)).state
        assertNull(revoked.permission)
        reject(revoked, ComputerMachine.Fact.PermissionsChecked(lease, true))
    }

    @Test fun bothToolsRequireFreshOwnReferencesAndConsumeThemBeforeMutation() {
        for (tool in ComputerMachine.Tool.entries) {
            val observed = observed(tool)
            val action = if (tool == ComputerMachine.Tool.DESKTOP) ComputerMachine.Action.CLICK else ComputerMachine.Action.INVOKE
            reject(observed, invoke(observed, action, "input", "other", 3))
            reject(observed, invoke(observed, action, "input", "reference", 30_000_000_003L))
            val started = ComputerMachine.reduce(observed, invoke(observed, action, "input", "reference", 3))
            assertIs<ComputerMachine.Effect.Invoke>(started.effects.single())
            assertFalse(tool in started.state.references)
            assertEquals(ComputerMachine.Outcome.PENDING, started.state.calls.getValue("input").outcome)
            reject(started.state, invoke(started.state, action, "second", "reference", 4))
        }
    }

    @Test fun unknownMutationRequiresObservationAndNeverPermitsRepeatingTheCallIdentity() {
        val observed = observed()
        val invocation = invoke(observed, ComputerMachine.Action.CLICK, "input", "reference", 3)
        val executing = ComputerMachine.reduce(observed, invocation).state
        val lease = checkNotNull(executing.grant).lease
        val unknown = ComputerMachine.reduce(executing, ComputerMachine.Fact.Failed(lease, "input", true)).state
        assertEquals(ComputerMachine.Outcome.UNKNOWN, unknown.calls.getValue("input").outcome)
        reject(unknown, invocation)
        reject(unknown, invoke(unknown, ComputerMachine.Action.CLICK, "another-input", "reference", 4))
        val capturing = ComputerMachine.reduce(unknown, invoke(unknown, ComputerMachine.Action.SCREENSHOT, "observe", atNanos = 4)).state
        val refreshed = ComputerMachine.reduce(capturing, ComputerMachine.Fact.Returned(lease, "observe",
            ComputerMachine.Reference("new-reference", ComputerMachine.Tool.DESKTOP, 5))).state
        reject(refreshed, invocation)
        assertIs<ComputerMachine.Effect.Invoke>(ComputerMachine.reduce(refreshed,
            invoke(refreshed, ComputerMachine.Action.CLICK, "another-input", "new-reference", 6)).effects.single())
    }

    @Test fun revocationDuringAnInputPreservesUnknownAndStaleReleaseCannotRevokeANewLease() {
        val observed = observed()
        val running = ComputerMachine.reduce(observed, invoke(observed, ComputerMachine.Action.CLICK, "input", "reference", 3)).state
        val oldLease = checkNotNull(running.grant).lease
        val revoked = ComputerMachine.reduce(running, ComputerMachine.Intent.Revoke(oldLease))
        assertNull(revoked.state.grant)
        assertIs<ComputerMachine.Effect.Release>(revoked.effects.single())
        assertEquals(ComputerMachine.Outcome.UNKNOWN, revoked.state.calls.getValue("input").outcome)
        reject(revoked.state, ComputerMachine.Fact.Returned(oldLease, "input"))
        val next = ComputerMachine.reduce(revoked.state, ComputerMachine.Intent.Begin(request.copy(id = "next"))).state
        assertNotEquals(oldLease, next.grant?.lease)
        assertEquals(next, ComputerMachine.reduce(next, ComputerMachine.Intent.Revoke(oldLease)).state)
    }

    @Test fun restoredStateNeverGrantsInputOrReplaysAnyEffect() {
        val samples = listOf(ready(), observed(), ComputerMachine.reduce(observed(),
            invoke(observed(), ComputerMachine.Action.CLICK, "input", "reference", 3)).state)
        for (sample in samples) {
            val restored = ComputerMachine.reduce(sample, ComputerMachine.Fact.Restored("fresh-owner"))
            assertNull(restored.state.grant)
            assertNull(restored.state.permission)
            assertTrue(restored.state.references.isEmpty())
            assertTrue(restored.effects.isEmpty())
            reject(restored.state, ComputerMachine.Intent.Begin(request))
        }
    }

    @Test fun unknownPersistenceRevokesImmediatelyAndBlocksFurtherAcquisition() {
        val state = observed()
        val unknown = ComputerMachine.reduce(state, ComputerMachine.Fact.PersistenceUnknown)
        assertNull(unknown.state.grant)
        assertTrue(unknown.state.persistenceUnknown)
        assertIs<ComputerMachine.Effect.Release>(unknown.effects.single())
        reject(unknown.state, ComputerMachine.Intent.Begin(request.copy(id = "next")))
        reject(unknown.state, ComputerMachine.Intent.Enable("session", ComputerAccess.CONTROL, checkNotNull(ComputerMachine.policyRef(state))))
        assertTrue(ComputerMachine.reduce(unknown.state, ComputerMachine.Fact.Restored("fresh-owner")).state.persistenceUnknown)
    }

    @Test fun changingPoliciesCannotClearUnknownPersistenceOrFailedResourceRelease() {
        for (failure in listOf(ComputerMachine.Fact.PersistenceUnknown, ComputerMachine.Fact.ReleaseFailed)) {
            val failed = ComputerMachine.reduce(ready(), failure).state
            val off = ComputerMachine.reduce(failed, ComputerMachine.Intent.Configure(ComputerAccess.OFF, ComputerAccess.OFF)).state
            val upgraded = ComputerMachine.reduce(off, configure).state
            reject(upgraded, ComputerMachine.Intent.Begin(request.copy(id = "next")))
            assertNull(ComputerMachine.policyRef(upgraded))
            reject(upgraded, ComputerMachine.Intent.Enable("session", ComputerAccess.CONTROL, ComputerPolicyRef(upgraded.incarnation, upgraded.policyRevision)))
            assertNull(upgraded.grant)
        }
        val failed = ComputerMachine.reduce(ready(), ComputerMachine.Fact.ReleaseFailed).state
        val restarted = ComputerMachine.reduce(failed, ComputerMachine.Fact.Restored("fresh-owner")).state
        assertFalse(restarted.resourceFailure)
        reject(restarted, ComputerMachine.Intent.Begin(request))
        assertNotNull(ComputerMachine.reduce(restarted, ComputerMachine.Intent.Begin(request.copy(id = "next"))).state.grant)
    }

    @Test fun applicationScreenshotCannotCreateBackgroundInputReferences() {
        val state = ready()
        val started = ComputerMachine.reduce(state, invoke(state, ComputerMachine.Action.WINDOW_SCREENSHOT)).state
        reject(started, ComputerMachine.Fact.Returned(checkNotNull(state.grant).lease, "call",
            ComputerMachine.Reference("reference", ComputerMachine.Tool.APPLICATION, 2)))
    }

    @Test fun viewOnlyPoliciesRejectControlForBothTools() {
        var state = ComputerMachine.reduce(initial(), ComputerMachine.Intent.Configure(ComputerAccess.SCREEN, ComputerAccess.SCREEN)).state
        state = ComputerMachine.reduce(state, ComputerMachine.Intent.Begin(request)).state
        reject(state, invoke(state, ComputerMachine.Action.CLICK))
        reject(state, invoke(state, ComputerMachine.Action.INVOKE))
        assertIs<ComputerMachine.Effect.Invoke>(ComputerMachine.reduce(state, invoke(state, ComputerMachine.Action.SCREENSHOT)).effects.single())
    }
}
