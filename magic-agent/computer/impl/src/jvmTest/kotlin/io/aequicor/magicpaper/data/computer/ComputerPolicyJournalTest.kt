package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ComputerPolicyJournalTest {
    private suspend fun DesktopComputerUse.policy(): ComputerPolicyRef = withTimeout(5_000) {
        while (capturePolicy() == null) delay(1)
        checkNotNull(capturePolicy())
    }
    private class Desktop(private val permission: () -> Unit = {}) : ComputerDesktop by FakeComputerDesktop() {
        val permissions = AtomicInteger()
        override fun checkPermissions(access: ComputerAccess, request: Boolean) { permissions.incrementAndGet(); permission() }
    }

    @Test fun staleCaptureNeverReachesPermissionsEvenAfterTheSamePolicyIsConfirmedAgain(): Unit = runBlocking {
        val desktop = Desktop()
        val computer = testComputer(desktop)
        try {
            val old = computer.policy()
            computer.invalidatePolicy()
            computer.configure(ComputerAccess.OFF, ComputerAccess.OFF)
            assertFalse(computer.enable("session", ComputerAccess.CONTROL, old))
            assertEquals(0, desktop.permissions.get())
            assertNull(computer.grant("session"))
            assertTrue(computer.enable("session", ComputerAccess.CONTROL, computer.policy()))
            assertEquals(1, desktop.permissions.get())
        } finally { computer.close() }
    }

    @Test fun invalidationDoesNotWaitForEnableAcknowledgementAndSuppressesItsPermissionEffect(): Unit = runBlocking {
        val backing = InMemoryEventJournal()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (detail.contains("ConditionalEnable")) { entered.complete(Unit); release.await() }
                return backing.append(expected, operation, at, detail)
            }
        }
        val desktop = Desktop(); val computer = testComputer(desktop, journal = journal)
        try {
            val ref = computer.policy()
            val enabling = async { computer.enable("session", ComputerAccess.CONTROL, ref) }
            entered.await()
            assertTrue(computer.state.value.busy)
            computer.invalidatePolicy()
            assertNull(computer.state.value.sessionId)
            assertFalse(enabling.isCompleted)
            release.complete(Unit)
            assertFalse(enabling.await())
            assertEquals(0, desktop.permissions.get())
            assertNull(computer.grant("session"))
        } finally { release.complete(Unit); computer.close() }
    }

    @Test fun cancellingWhileEnableAckIsPendingRevokesOnlyItsProjectedPermission(): Unit = runBlocking {
        val backing = InMemoryEventJournal()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (detail.contains("ConditionalEnable")) { entered.complete(Unit); release.await() }
                return backing.append(expected, operation, at, detail)
            }
        }
        val desktop = Desktop(); val computer = testComputer(desktop, journal = journal)
        try {
            val ref = computer.policy()
            val enabling = async { computer.enable("session", ComputerAccess.CONTROL, ref) }
            entered.await()
            enabling.cancelAndJoin()
            assertTrue(enabling.isCancelled)
            assertNull(computer.state.value.sessionId)
            assertNull(computer.grant("session"))
            release.complete(Unit)
            assertEquals(0, desktop.permissions.get())
            assertTrue(computer.enable("session", ComputerAccess.SCREEN, ref))
            assertEquals(1, desktop.permissions.get())
        } finally { release.complete(Unit); computer.close() }
    }

    @Test fun configurationRevokesPendingOffPolicyPermissionWithoutWaitingForTheOperatingSystem(): Unit = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val desktop = Desktop {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Fixture permission release timed out" }
        }
        val computer = testComputer(desktop)
        try {
            val ref = computer.policy()
            val enabling = async(Dispatchers.Default) { computer.enable("session", ComputerAccess.CONTROL, ref) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            computer.configure(ComputerAccess.OFF, ComputerAccess.OFF)
            assertNull(computer.state.value.sessionId)
            assertFalse(enabling.isCompleted)
            release.countDown()
            assertFalse(enabling.await())
            assertNull(computer.grant("session"))
            assertEquals(1, desktop.permissions.get())
        } finally { release.countDown(); computer.close() }
    }

    @Test fun legacyAcceptedPermissionRestoresWithoutEffectsAndLegacyLiveAdmissionIsRejected(): Unit = runBlocking {
        val journal = InMemoryEventJournal()
        val json = Json { encodeDefaults = true }
        val inputs = listOf<ComputerMachine.Input>(ComputerMachine.Fact.Restored("old-owner"),
            ComputerMachine.LegacyInput.Enable("session", ComputerAccess.CONTROL),
            ComputerMachine.LegacyInput.Configure(ComputerAccess.OFF, ComputerAccess.OFF),
            ComputerMachine.Fact.PermissionsChecked(ComputerLease("session", "old-owner:2"), true))
        inputs.forEachIndexed { index, input ->
            val detail = buildJsonObject {
                put("id", "old-$index"); put("resetEpoch", 0)
                put("input", json.encodeToJsonElement(ComputerMachine.Input.serializer(), input))
            }.toString()
            journal.append("computer-authority:application", "computer-authority.input.v1", index.toLong(), detail)
        }
        val oldRecords = journal.read("computer-authority:application")
        val desktop = Desktop(); val computer = DesktopComputerUse(desktop, journal)
        try {
            withTimeout(5_000) { while (journal.read("computer-authority:application").size == oldRecords.size) delay(1) }
            assertNull(computer.capturePolicy())
            assertFalse(computer.enable("session", ComputerAccess.CONTROL, ComputerPolicyRef("old-owner", 0)))
            assertNull(computer.grant("session"))
            assertEquals(0, desktop.permissions.get())
            assertEquals(oldRecords, journal.read("computer-authority:application").take(oldRecords.size))
        } finally { computer.close() }
        val authority = ComputerAuthority(InMemoryEventJournal()) {}
        try {
            withTimeout(5_000) { while (authority.state.incarnation.isBlank()) delay(1) }
            assertFailsWith<ComputerAuthorityRejected> {
                authority.dispatch(ComputerMachine.LegacyInput.Enable("session", ComputerAccess.CONTROL))
            }
            assertNull(authority.state.permission)
        } finally { authority.close(); authority.awaitClosed() }
    }

    @Test fun resetRejectsOldPolicyRefAfterARealOwnerIncarnationChange(): Unit = runBlocking {
        val desktop = Desktop(); val computer = testComputer(desktop)
        try {
            val old = computer.policy()
            computer.prepareForReset(); computer.resumeAfterReset()
            computer.configure(ComputerAccess.OFF, ComputerAccess.OFF)
            val current = computer.policy()
            assertNotEquals(old.incarnation, current.incarnation)
            assertFalse(computer.enable("session", ComputerAccess.CONTROL, old))
            assertEquals(0, desktop.permissions.get())
            assertTrue(computer.enable("session", ComputerAccess.CONTROL, current))
            assertEquals(1, desktop.permissions.get())
        } finally { computer.close() }
    }
}
