package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name every journaled branch of [UsageMachine.Input] is written under, pinned.
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
class UsageMachineWireFormatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val pinned = setOf(
        "io.aequicor.magicpaper.domain.UsageMachine.Fact.ContextObserved",
        "io.aequicor.magicpaper.domain.UsageMachine.Fact.CumulativeObserved",
        "io.aequicor.magicpaper.domain.UsageMachine.Fact.Initialized",
        "io.aequicor.magicpaper.domain.UsageMachine.Fact.PersistenceUnknown",
        "io.aequicor.magicpaper.domain.UsageMachine.Fact.Recorded",
        "io.aequicor.magicpaper.domain.UsageMachine.Intent.Clear",
        "io.aequicor.magicpaper.domain.UsageMachine.Intent.Import",
    )

    /** A branch added later must be pinned here too: the hierarchy holds exactly 7 names. */
    @Test fun everyBranchIsWrittenUnderItsPinnedName() {
        val sealed = UsageMachine.Input.serializer().descriptor.getElementDescriptor(1)
        assertEquals(pinned, (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet())
        assertEquals(7, pinned.size)
    }

    @Test fun everyBranchClassIsWrittenUnderItsOwnPinnedName() {
        val owned = listOf(
            UsageMachine.Fact.ContextObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.UsageMachine.Fact.ContextObserved",
            UsageMachine.Fact.CumulativeObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.UsageMachine.Fact.CumulativeObserved",
            UsageMachine.Fact.Initialized.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.UsageMachine.Fact.Initialized",
            UsageMachine.Fact.PersistenceUnknown.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.UsageMachine.Fact.PersistenceUnknown",
            UsageMachine.Fact.Recorded.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.UsageMachine.Fact.Recorded",
            UsageMachine.Intent.Clear.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.UsageMachine.Intent.Clear",
            UsageMachine.Intent.Import.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.UsageMachine.Intent.Import",
        )
        assertEquals(7, owned.size)
        for ((written, name) in owned) assertEquals(name, written)
        assertEquals(pinned, owned.map { it.second }.toSet())
    }
}
