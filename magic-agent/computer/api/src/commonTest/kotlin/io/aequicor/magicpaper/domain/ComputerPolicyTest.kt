package io.aequicor.magicpaper.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.*

class ComputerPolicyTest {
    private fun initial() = ComputerMachine.reduce(ComputerMachine.initial(), ComputerMachine.Fact.Restored("owner")).state
    private fun configured() = ComputerMachine.reduce(initial(), ComputerMachine.Intent.Configure(ComputerAccess.OFF, ComputerAccess.OFF)).state
    private fun enable(state: ComputerMachine.State) = ComputerMachine.Intent.Enable("session", ComputerAccess.CONTROL,
        ComputerPolicyRef(state.incarnation, state.policyRevision))
    private fun rejected(state: ComputerMachine.State, input: ComputerMachine.Input) {
        val transition = ComputerMachine.reduce(state, input)
        assertEquals(state, transition.state)
        assertIs<ComputerMachine.Effect.Reject>(transition.effects.single())
    }

    @Test fun conditionalEnableRejectsEveryInvalidatedPolicyBeforePermissionEffects() {
        val original = configured()
        val command = enable(original)
        val states = listOf(
            ComputerMachine.reduce(original, ComputerMachine.Intent.SuspendPolicy).state,
            ComputerMachine.reduce(original, ComputerMachine.Intent.Configure(ComputerAccess.OFF, ComputerAccess.OFF)).state,
            ComputerMachine.reduce(original, ComputerMachine.Intent.Configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)).state,
            ComputerMachine.reduce(original, ComputerMachine.Fact.Restored("new-owner")).state,
            ComputerMachine.reduce(original, ComputerMachine.Fact.PersistenceUnknown).state,
            ComputerMachine.reduce(original, ComputerMachine.Fact.ReleaseFailed).state,
        )
        for (state in states) rejected(state, command)
        assertNull(ComputerMachine.policyRef(initial()))
        rejected(initial(), enable(initial()))
        val fresh = ComputerMachine.reduce(original, command)
        assertIs<ComputerMachine.Effect.CheckPermissions>(fresh.effects.single())
    }

    @Test fun offToOffConfigurationAndSuspensionRevokeAnInFlightManualPermission() {
        val original = configured()
        val pending = ComputerMachine.reduce(original, enable(original)).state
        val lease = assertNotNull(pending.permission).lease
        for (input in listOf(ComputerMachine.Intent.SuspendPolicy,
            ComputerMachine.Intent.Configure(ComputerAccess.OFF, ComputerAccess.OFF))) {
            val revoked = ComputerMachine.reduce(pending, input)
            assertNull(revoked.state.permission)
            assertNull(revoked.state.grant)
            assertEquals(lease, assertIs<ComputerMachine.Effect.Release>(revoked.effects.single()).lease)
            rejected(revoked.state, ComputerMachine.Fact.PermissionsChecked(lease, true))
            rejected(revoked.state, enable(original))
        }
    }

    @Test fun repeatedInvalidationNeverConfirmsAndConfigureCannotClearUnknown() {
        val original = configured()
        val first = ComputerMachine.reduce(original, ComputerMachine.Intent.SuspendPolicy).state
        val second = ComputerMachine.reduce(first, ComputerMachine.Intent.SuspendPolicy).state
        assertTrue(second.policyRevision > first.policyRevision)
        assertFalse(second.policyConfirmed)
        assertNull(ComputerMachine.policyRef(second))
        rejected(second, enable(second))
        val unknown = ComputerMachine.reduce(second, ComputerMachine.Fact.PersistenceUnknown).state
        val reapplied = ComputerMachine.reduce(unknown, ComputerMachine.Intent.Configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)).state
        rejected(reapplied, enable(reapplied))
        assertTrue(reapplied.persistenceUnknown)
        assertNull(ComputerMachine.policyRef(reapplied))
    }

    @Test fun historicalUnconditionalInputsReplayExactlyButCannotBeDispatchedLive() {
        val json = Json { encodeDefaults = true }
        val oldEnable = json.decodeFromString<ComputerMachine.Input>("""{"type":"Enable","sessionId":"session","access":"CONTROL"}""")
        val oldOff = json.decodeFromString<ComputerMachine.Input>("""{"type":"Configure","desktop":"OFF","application":"OFF"}""")
        assertIs<ComputerMachine.LegacyInput.Enable>(oldEnable)
        assertIs<ComputerMachine.LegacyInput.Configure>(oldOff)
        rejected(initial(), oldEnable)
        rejected(initial(), oldOff)
        val pending = ComputerMachine.replay(initial(), oldEnable).state
        // Old OFF/OFF left pending permission intact; preserve its exact historical lease.
        val unchanged = ComputerMachine.replay(pending, oldOff).state
        assertEquals(pending.permission, unchanged.permission)
        val lease = assertNotNull(unchanged.permission).lease
        val granted = ComputerMachine.replay(unchanged, ComputerMachine.Fact.PermissionsChecked(lease, true)).state
        assertEquals(lease, granted.grant?.lease)
        val restored = ComputerMachine.reduce(granted, ComputerMachine.Fact.Restored("reopened"))
        assertTrue(restored.effects.isEmpty())
        assertNull(restored.state.grant)
        assertFalse(restored.state.policyConfirmed)
        rejected(restored.state, enable(restored.state))
    }

    @Test fun conditionalInputRequiresItsSerializedExactProof() {
        val json = Json { encodeDefaults = true }
        val state = configured()
        val input: ComputerMachine.Input = enable(state)
        val encoded = json.encodeToString(ComputerMachine.Input.serializer(), input)
        assertEquals(input, json.decodeFromString<ComputerMachine.Input>(encoded))
        assertContains(encoded, "ConditionalEnable")
        assertFailsWith<SerializationException> {
            json.decodeFromString<ComputerMachine.Input>("""{"type":"ConditionalEnable","sessionId":"session","access":"CONTROL"}""")
        }
        rejected(state, enable(state).copy(expectedPolicy = ComputerPolicyRef("foreign", state.policyRevision)))
    }
}
