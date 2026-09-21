package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class DefaultCommandChecksTest {
    private class Driver : CheckProcessDriver {
        var prepares = 0; var releases = 0; var stops = 0; var attestations = 0
        var prepareFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        var onReleased: (() -> Unit)? = null
        var awaitCompletion: CompletableDeferred<Unit>? = null
        var probePath: String? = null
        var probes = 0
        var probeFailure: Throwable? = null
        var beforeProbe: CompletableDeferred<Unit>? = null
        var probeEntered: CompletableDeferred<Unit>? = null
        override suspend fun probeWorkspace(): String? = probePath
        override suspend fun createProbe(ref: CheckRef): CheckProbe {
            probes++
            probeEntered?.complete(Unit)
            beforeProbe?.await()
            probeFailure?.let { throw it }
            return object : CheckProbe {
                override val command = CheckCommand(ref, checkNotNull(probePath), listOf("probe"))
                override suspend fun verify(result: CheckResult) { check(result.exitCode == 0) }
            }
        }
        override suspend fun prepare(command: CheckCommand, receiptId: String, authority: CheckAuthorityRecorder): PreparedCommandCheck {
            prepares++
            prepareFailure?.let { throw it }
            return object : PreparedCommandCheck {
                override val receipt = CheckProcessReceipt(receiptId, "test-group", 123)
                override suspend fun release() { releases++; onReleased?.invoke() }
                override suspend fun awaitResult(progress: (String) -> Unit): CheckResult {
                    awaitCompletion?.await()
                    progress("output")
                    return CheckResult("output", 0)
                }
                override suspend fun stopAndConfirm(): CheckCleanup { stops++; cleanupFailure?.let { throw it }; return CheckCleanup("group-stopped", "authority-restored") }
                override suspend fun attest(): String { attestations++; return "artifacts" }
                override suspend fun discard() { }
            }
        }
    }
    private suspend fun fixture(block: suspend (String, EventJournal, KeyValueStore, Driver) -> Unit) {
        val workspace = Files.createTempDirectory("check-owner-test-")
        try { block(workspace.toRealPath().toString(), InMemoryEventJournal(), InMemoryKeyValueStore(), Driver()) }
        finally { Files.deleteIfExists(workspace) }
    }
    private fun command(path: String) = CheckCommand(CheckRef(CheckScope("project", "session", "request", 0), "call"), path, listOf("tool"))

    @Test fun duplicateExactCallAndReopenReturnReceiptWithoutLaunchingAgain() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        val command = command(path)
        assertEquals(0, owner.run(command).exitCode)
        assertEquals(0, owner.run(command).exitCode)
        val reopened = DefaultCommandChecks(events, payloads, driver)
        assertEquals(0, reopened.run(command).exitCode)
        assertEquals(1, driver.prepares); assertEquals(1, driver.releases)
        assertEquals(1, driver.stops); assertEquals(1, driver.attestations)
    } }

    @Test fun completedAndChangedCallsAreResolvedBeforeAnyProbeAfterReopen() = runTest { fixture { path, events, payloads, driver ->
        val probe = Files.createTempDirectory("check-probe-test-")
        try {
            driver.probePath = probe.toRealPath().toString()
            val input = command(path)
            DefaultCommandChecks(events, payloads, driver).run(input)
            assertEquals(1, driver.probes)
            assertEquals(2, driver.prepares)
            driver.probeFailure = IOException("probe must not execute during a saved lookup")
            val reopened = DefaultCommandChecks(events, payloads, driver)
            assertEquals(0, reopened.run(input).exitCode)
            assertFailsWith<CheckRejected> { reopened.run(input.copy(arguments = listOf("different"))) }
            assertEquals(1, driver.probes)
            assertEquals(2, driver.prepares)
        } finally { Files.deleteIfExists(probe) }
    } }

    @Test fun unknownWorkspaceRejectsBeforeProbeAndProbeWorkspaceCanAlsoBeTarget() = runTest { fixture { path, events, payloads, driver ->
        driver.probePath = path
        val owner = DefaultCommandChecks(events, payloads, driver)
        withContext(Dispatchers.Default) { withTimeout(5_000) { owner.run(command(path)) } }
        assertEquals(1, driver.probes)
        assertEquals(2, driver.prepares, "The distinct probe and user command must both complete without nested workspace locks")
        driver.prepareFailure = IOException("unknown native preparation")
        val next = command(path).copy(ref = command(path).ref.copy(callId = "unknown"))
        assertFailsWith<CheckOutcomeUnknown> { owner.run(next) }
        driver.probeFailure = IOException("probe must not run for an unknown workspace")
        val reopened = DefaultCommandChecks(events, payloads, driver)
        assertFailsWith<CheckRejected> { reopened.run(command(path)) }
        assertEquals(1, driver.probes)
        assertEquals(3, driver.prepares)
    } }

    @Test fun persistenceUnknownOutranksACompletedResultDuringInspection() = runTest { fixture { path, events, payloads, driver ->
        var failWrites = false
        val broken = object : EventJournal by events {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (failWrites) throw IOException("uncertain journal write")
                return events.append(expected, operation, at, detail)
            }
        }
        val owner = DefaultCommandChecks(broken, payloads, driver)
        val input = command(path)
        assertEquals(0, owner.run(input).exitCode)
        failWrites = true
        assertFailsWith<CheckOutcomeUnknown> { owner.run(input.copy(ref = input.ref.copy(callId = "later"))) }
        assertFailsWith<CheckOutcomeUnknown> { owner.inspect(input.ref) }
        assertEquals(1, driver.prepares)
    } }

    @Test fun resetCancelsAdmittedCallWaitingForProbeEvenWhenResetIsRolledBack() = runTest { fixture { path, events, payloads, driver ->
        val probe = Files.createTempDirectory("check-reset-probe-test-")
        try {
            driver.probePath = probe.toRealPath().toString()
            driver.probeEntered = CompletableDeferred()
            driver.beforeProbe = CompletableDeferred()
            val owner = DefaultCommandChecks(events, payloads, driver)
            val oldCall = async { owner.run(command(path)) }
            driver.probeEntered!!.await()
            owner.prepareForReset()
            owner.resumeAfterReset() // No wipe: the original journal epoch is deliberately retained.
            driver.beforeProbe!!.complete(Unit)
            assertFailsWith<CancellationException> { oldCall.await() }
            assertEquals(0, driver.prepares)
            driver.beforeProbe = null
            val cancelled = owner.run(command(path))
            assertNull(cancelled.exitCode)
            assertEquals("Проверка отменена до запуска", cancelled.blockedReason)
            assertEquals(0, driver.prepares)
            assertEquals(0, owner.run(command(path).copy(ref = command(path).ref.copy(callId = "fresh-after-reset"))).exitCode)
            assertEquals(2, driver.prepares)
        } finally { Files.deleteIfExists(probe) }
    } }

    @Test fun releaseRequiresDurablyRecordedProcessAndExplicitReleaseInput() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        driver.onReleased = {
            val values = payloads.keys("check-input:").map { checkNotNull(payloads.read(it)) }
            assertTrue(values.any { "ProcessPrepared" in it })
            assertTrue(values.any { "Release" in it })
        }
        owner.run(command(path))
    } }

    @Test fun changedDuplicatePayloadNeverStartsAnotherCommand() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        val command = command(path)
        owner.run(command)
        assertFailsWith<CheckRejected> { owner.run(command.copy(arguments = listOf("other"))) }
        assertEquals(1, driver.prepares)
    } }

    @Test fun missingGroupProofBlocksArtifactsNewCallsAndResetEvenAfterReopen() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        driver.cleanupFailure = IOException("group outcome unknown")
        assertFailsWith<CheckOutcomeUnknown> { owner.run(command(path)) }
        assertEquals(0, driver.attestations)
        val reopened = DefaultCommandChecks(events, payloads, driver)
        assertFailsWith<CheckOutcomeUnknown> { reopened.prepareForReset() }
        reopened.resumeAfterReset()
        assertFailsWith<CheckRejected> { reopened.run(command(path).copy(ref = command(path).ref.copy(callId = "new"))) }
        assertEquals(1, driver.prepares)
    } }

    @Test fun onlyTypedPreparationFailureProvesNoDispatch() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        driver.prepareFailure = CheckNotDispatched("Проверка недоступна")
        val result = owner.run(command(path))
        assertNull(result.exitCode); assertEquals("Проверка недоступна", result.blockedReason)
        assertEquals(0, driver.releases)
        owner.prepareForReset()
    } }

    @Test fun ordinaryPreparationFailureRemainsUnknown() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        driver.prepareFailure = IOException("failure after native allocation")
        assertFailsWith<CheckOutcomeUnknown> { owner.run(command(path)) }
        assertFailsWith<CheckOutcomeUnknown> { owner.prepareForReset() }
        assertEquals(0, driver.releases)
    } }

    @Test fun cancellationAfterPrepareEnteredCannotInventNoDispatch() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        driver.prepareFailure = CancellationException("suspended native preparation")
        assertFailsWith<CancellationException> { owner.run(command(path)) }
        assertFailsWith<CheckOutcomeUnknown> { owner.prepareForReset() }
        assertEquals(0, driver.releases)
    } }

    @Test fun cancelledRunningCheckRetainsCancellationAndConfirmsCleanup() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        val released = CompletableDeferred<Unit>()
        driver.onReleased = { released.complete(Unit) }
        driver.awaitCompletion = CompletableDeferred()
        val running = async { owner.run(command(path)) }
        released.await()
        running.cancel()
        assertFailsWith<CancellationException> { running.await() }
        owner.prepareForReset()
        assertEquals(1, driver.stops)
        assertEquals(1, driver.attestations)
        val reopened = DefaultCommandChecks(events, payloads, driver)
        val result = reopened.inspect(command(path).ref)
        assertNull(result?.exitCode); assertEquals("Проверка отменена", result?.blockedReason)
    } }

    @Test fun originalCancellationSurvivesCleanupFailureAndResetStaysBlocked() = runTest { fixture { path, events, payloads, driver ->
        val owner = DefaultCommandChecks(events, payloads, driver)
        val released = CompletableDeferred<Unit>()
        driver.onReleased = { released.complete(Unit) }
        driver.awaitCompletion = CompletableDeferred()
        driver.cleanupFailure = IOException("cleanup failure")
        val running = async { owner.run(command(path)) }
        released.await(); running.cancel()
        assertFailsWith<CancellationException> { running.await() }
        assertFailsWith<CheckOutcomeUnknown> { owner.prepareForReset() }
        assertEquals(0, driver.attestations)
    } }

    @Test fun failedStopJournalCannotSuppressNativeCleanup() = runTest { fixture { path, events, payloads, driver ->
        var failWrites = false
        val broken = object : EventJournal by events {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (failWrites) throw IOException("stop journal unavailable")
                return events.append(expected, operation, at, detail)
            }
        }
        val owner = DefaultCommandChecks(broken, payloads, driver)
        val released = CompletableDeferred<Unit>()
        driver.onReleased = { released.complete(Unit) }
        driver.awaitCompletion = CompletableDeferred()
        val running = async { owner.run(command(path)) }
        released.await(); failWrites = true; running.cancel()
        assertFailsWith<CancellationException> { running.await() }
        assertEquals(1, driver.stops, "Native cleanup must be attempted even if Stop cannot be journaled")
        assertEquals(0, driver.attestations)
        assertFailsWith<CheckOutcomeUnknown> { owner.prepareForReset() }
    } }
}
