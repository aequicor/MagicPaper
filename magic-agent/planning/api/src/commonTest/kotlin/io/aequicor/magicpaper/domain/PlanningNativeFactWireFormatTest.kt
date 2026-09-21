package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name every branch of [PlanningNativeFact] is written under, pinned.
 *
 * `PlanningMachine.Fact.NativeObserved` carries one, and that fact is journaled with the rest of
 * [PlanningMachine.Input]. None of the branches had a `@SerialName`, so the polymorphic discriminator of each is
 * the full name of the class, and moving or renaming one would silently orphan every record written under the old
 * name. Each now names itself with exactly the string it has always been written under, so nothing stored has to
 * be migrated. The names were read from the encoder before any was pinned, and this test passed unchanged after.
 *
 * A class is named here by its own serializer: swapping the pins of two branches would leave the set of names
 * unchanged and decode the records of one as the other.
 */
class PlanningNativeFactWireFormatTest {
    private val pinned = setOf(
        "io.aequicor.magicpaper.domain.PlanningNativeFact.Acknowledged",
        "io.aequicor.magicpaper.domain.PlanningNativeFact.ConsumptionObserved",
        "io.aequicor.magicpaper.domain.PlanningNativeFact.ReleaseAuthorized",
        "io.aequicor.magicpaper.domain.PlanningNativeFact.RequestAdmitted",
    )

    /** A branch added later must be pinned here too: the hierarchy holds exactly these four names. */
    @Test fun everyBranchIsWrittenUnderItsPinnedName() {
        val sealed = PlanningNativeFact.serializer().descriptor.getElementDescriptor(1)
        assertEquals(pinned, (0 until sealed.elementsCount).map { sealed.getElementName(it) }.toSet())
        assertEquals(4, pinned.size)
    }

    @Test fun everyBranchClassIsWrittenUnderItsOwnPinnedName() {
        val owned = listOf(
            PlanningNativeFact.Acknowledged.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningNativeFact.Acknowledged",
            PlanningNativeFact.ConsumptionObserved.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningNativeFact.ConsumptionObserved",
            PlanningNativeFact.ReleaseAuthorized.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningNativeFact.ReleaseAuthorized",
            PlanningNativeFact.RequestAdmitted.serializer().descriptor.serialName to "io.aequicor.magicpaper.domain.PlanningNativeFact.RequestAdmitted",
        )
        assertEquals(4, owned.size)
        for ((written, name) in owned) assertEquals(name, written)
        assertEquals(pinned, owned.map { it.second }.toSet())
    }
}
