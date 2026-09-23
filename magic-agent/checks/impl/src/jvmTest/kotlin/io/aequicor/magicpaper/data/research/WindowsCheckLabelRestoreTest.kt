package io.aequicor.magicpaper.data.research

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Restoration is proven by the integrity level an object carries, not by the inheritance bookkeeping
 * Windows recomputes on every label write. Requiring byte equality of the whole SDDL made restoration
 * unprovable on every artifact, which failed the OS sandbox probe and refused every sandboxed check —
 * including the Git reads that decide whether worktree mode is available.
 */
class WindowsCheckLabelRestoreTest {
    private val low = "S:(ML;OICI;NW;;;LW)"

    @Test fun inheritanceBookkeepingAddedByWindowsDoesNotFailRestoration() {
        // No label before, no label after: the ordinary case for a project's artifact directory.
        assertTrue(WindowsCheckAuthority.labelRestored("", ""))
        // What the resource manager really reads back after a write: `AI` on the descriptor, `ID` on an
        // ACE it propagated from the parent. Both are bookkeeping, neither changes the level.
        assertTrue(WindowsCheckAuthority.labelRestored(low, "S:AI(ML;OICIID;NW;;;LW)"))
        assertTrue(WindowsCheckAuthority.labelRestored("S:AI(ML;OICIID;NW;;;LW)", low))
        assertTrue(WindowsCheckAuthority.labelRestored("S:PAI(ML;OICI;NW;;;LW)", "S:P(ML;OICI;NW;;;LW)"))
        assertTrue(WindowsCheckAuthority.labelRestored(low, low))
    }

    @Test fun leftoverSandboxLevelIsNotRestored() {
        assertFalse(WindowsCheckAuthority.labelRestored("", "S:AI(ML;OICIID;NW;;;LW)"))
        assertFalse(WindowsCheckAuthority.labelRestored("", "S:(ML;;NW;;;LW)"))
        // An artifact of the run may not keep an explicit level the object did not have before.
        assertFalse(WindowsCheckAuthority.labelRestored("", "S:(ML;OICI;NW;;;ME)"))
    }

    @Test fun changedLevelPolicyOrProtectionIsNotRestored() {
        assertFalse(WindowsCheckAuthority.labelRestored(low, "S:(ML;OICI;NW;;;ME)"))
        assertFalse(WindowsCheckAuthority.labelRestored(low, "S:(ML;OICI;NR;;;LW)"))
        assertFalse(WindowsCheckAuthority.labelRestored(low, "S:(ML;;NW;;;LW)"))
        // Protection decides whether children keep inheriting the level.
        assertFalse(WindowsCheckAuthority.labelRestored(low, "S:P(ML;OICI;NW;;;LW)"))
        assertFalse(WindowsCheckAuthority.labelRestored("S:P(ML;OICI;NW;;;LW)", low))
    }

    @Test fun onlyIntegrityLabelDescriptorsAreCompared() {
        assertFailsWith<IllegalArgumentException> { WindowsCheckAuthority.labelRestored("D:(A;;FA;;;SY)", "") }
        assertFailsWith<IllegalArgumentException> { WindowsCheckAuthority.labelRestored("", "D:(A;;FA;;;SY)") }
    }
}
