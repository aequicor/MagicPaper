package io.aequicor.magicpaper.backend

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name every journaled branch of [NativeLifecycleMachine.Input] is written under, pinned.
 *
 * The inputs are journaled and replayed after a restart. None of these branches had a `@SerialName`, so the
 * polymorphic discriminator of each is the full name of the class *with its nesting* and moving or renaming a
 * branch, or splitting its parent, would silently orphan every record written under the old name. Each branch
 * now names itself with exactly the string it has always been written under, so nothing stored has to be migrated.
 *
 * The names below were read from the encoder before any was pinned, and this test passed unchanged after. Only the
 * discriminator changes when a name is pinned, so the names are what is pinned; a class is named here by its own
 * serializer because swapping the pins of two branches would leave the set of names unchanged and decode the
 * records of one as the other.
 */
class NativeLifecycleMachineWireFormatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val pinned = setOf(
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Accepted",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Attached",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Closed",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.DeliveryRequested",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.LaunchRequested",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.NeighbourMissing",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.NoDispatchConfirmed",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.PersistenceUnknown",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Restored",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.RunFinished",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Stopped",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Stopping",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Terminal",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Unavailable",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Acknowledge",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.AcknowledgeNoDispatch",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Begin",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Cancel",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Close",
        "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Stop",
    )

    /** A branch added later must be pinned here too: the hierarchy holds exactly 20 names. */
    @Test fun everyBranchIsWrittenUnderItsPinnedName() {
        val sealed = NativeLifecycleMachine.Input.serializer().descriptor.getElementDescriptor(1)
        assertEquals(pinned, (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet())
        assertEquals(20, pinned.size)
    }

    @Test fun everyBranchClassIsWrittenUnderItsOwnPinnedName() {
        val owned = listOf(
            NativeLifecycleMachine.Fact.Accepted.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Accepted",
            NativeLifecycleMachine.Fact.Attached.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Attached",
            NativeLifecycleMachine.Fact.Closed.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Closed",
            NativeLifecycleMachine.Fact.DeliveryRequested.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.DeliveryRequested",
            NativeLifecycleMachine.Fact.LaunchRequested.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.LaunchRequested",
            NativeLifecycleMachine.Fact.NeighbourMissing.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.NeighbourMissing",
            NativeLifecycleMachine.Fact.NoDispatchConfirmed.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.NoDispatchConfirmed",
            NativeLifecycleMachine.Fact.PersistenceUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.PersistenceUnknown",
            NativeLifecycleMachine.Fact.Restored.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Restored",
            NativeLifecycleMachine.Fact.RunFinished.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.RunFinished",
            NativeLifecycleMachine.Fact.Stopped.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Stopped",
            NativeLifecycleMachine.Fact.Stopping.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Stopping",
            NativeLifecycleMachine.Fact.Terminal.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Terminal",
            NativeLifecycleMachine.Fact.Unavailable.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Unavailable",
            NativeLifecycleMachine.Intent.Acknowledge.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Acknowledge",
            NativeLifecycleMachine.Intent.AcknowledgeNoDispatch.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.AcknowledgeNoDispatch",
            NativeLifecycleMachine.Intent.Begin.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Begin",
            NativeLifecycleMachine.Intent.Cancel.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Cancel",
            NativeLifecycleMachine.Intent.Close.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Close",
            NativeLifecycleMachine.Intent.Stop.serializer().descriptor.serialName to "io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Stop",
        )
        assertEquals(20, owned.size)
        for ((written, name) in owned) assertEquals(name, written)
        assertEquals(pinned, owned.map { it.second }.toSet())
    }

    @Test fun aRecordWrittenBeforeThePinningStillDecodes() {
        val records = listOf<Pair<NativeLifecycleMachine.Input, String>>(
            NativeLifecycleMachine.Fact.Closed to """{"type":"io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Closed"}""",
            NativeLifecycleMachine.Fact.PersistenceUnknown to """{"type":"io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.PersistenceUnknown"}""",
            NativeLifecycleMachine.Fact.Restored to """{"type":"io.aequicor.magicpaper.backend.NativeLifecycleMachine.Fact.Restored"}""",
            NativeLifecycleMachine.Intent.Close to """{"type":"io.aequicor.magicpaper.backend.NativeLifecycleMachine.Intent.Close"}""",
        )
        for ((value, stored) in records) {
            assertEquals(stored, json.encodeToString(NativeLifecycleMachine.Input.serializer(), value))
            assertEquals(value, json.decodeFromString(NativeLifecycleMachine.Input.serializer(), stored))
        }
    }
}
