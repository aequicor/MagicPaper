package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class CheckPreparationOwnerTest {
    private class Driver : CheckProcessDriver {
        var probePath: String? = null
        var probeFailure: Throwable? = null
        var metadata = false
        var nativeFailure: Throwable? = null
        var probes = 0
        var onProbe: suspend () -> Unit = {}
        val prepared = mutableListOf<CheckCommand>()
        val released = mutableListOf<CheckCommand>()
        override suspend fun probeWorkspace() = probePath
        override suspend fun createProbe(ref: CheckRef): CheckProbe {
            probes++
            onProbe()
            probeFailure?.let { throw it }
            return object : CheckProbe {
                override val command = CheckCommand(ref, checkNotNull(probePath), listOf("probe"))
                override suspend fun verify(result: CheckResult) { check(result.exitCode == 0) }
            }
        }
        override suspend fun needsGitMetadata(command: CheckCommand) = metadata
        override suspend fun prepare(command: CheckCommand, receiptId: String, authority: CheckAuthorityRecorder): PreparedCommandCheck {
            prepared += command
            nativeFailure?.let { throw it }
            return object : PreparedCommandCheck {
                override val receipt = CheckProcessReceipt(receiptId, "controlled-group", 123)
                override suspend fun release() { released += command }
                override suspend fun awaitResult(progress: (String) -> Unit) = CheckResult("", 0,
                    binaryOutput = if (command.outputMode == CheckOutputMode.BINARY_STDOUT) CheckOutputRef(receiptId, 0, "a".repeat(64)) else null)
                override suspend fun stopAndConfirm() = CheckCleanup("group", "authority")
                override suspend fun attest() = "artifact"
                override suspend fun discard() = Unit
            }
        }
    }
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("check-preparation-owner-").toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace")).toString()
        val probe = Files.createDirectory(root.resolve("probe")).toString()
        val sibling = Files.createDirectory(root.resolve("sibling")).toString()
        val events = InMemoryEventJournal()
        val payloads = InMemoryKeyValueStore()
        val driver = Driver()
        val command = CheckCommand(CheckRef(CheckScope("project", "session", "request", 3), "call"), workspace, listOf("build"))
        fun owner(events: EventJournal = this.events) = DefaultCommandChecks(events, payloads, driver)
        fun input(detail: String): String {
            val id = Json.parseToJsonElement(detail).jsonObject.getValue("id").jsonPrimitive.content
            return checkNotNull(payloads.read(payloads.keys("check-input:").single { it.endsWith(":$id") }))
        }
        override fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun unavailableProbeProducesExactBlockedReceiptAndOnlyFreshRequestCanTryAgain() = runBlocking<Unit> {
        Fixture().use { f ->
            f.driver.probePath = f.probe
            f.driver.probeFailure = IOException("controlled probe preparation refusal")
            val owner = f.owner()
            assertFailsWith<IOException> { owner.run(f.command) }
            val blocked = assertNotNull(owner.inspect(f.command.ref))
            assertNull(blocked.exitCode)
            assertNotNull(blocked.blockedReason)
            assertTrue(owner.unresolved(f.workspace).isEmpty())
            assertTrue(f.driver.prepared.isEmpty())
            val reopened = f.owner()
            assertEquals(blocked, reopened.run(f.command))
            assertEquals(1, f.driver.probes)
            f.driver.probeFailure = null
            assertEquals(0, reopened.run(f.command.copy(ref = f.command.ref.copy(callId = "fresh"))).exitCode)
            assertEquals(2, f.driver.probes)
            assertEquals(2, f.driver.released.size)
            assertTrue(f.driver.released.none { it.ref == f.command.ref })
        }
    }

    @Test fun alreadyCancelledCallerStillRecordsNoDispatchBeforeTheIoHandoff() = runBlocking<Unit> {
        Fixture().use { f ->
            val owner = f.owner()
            val cancellation = CancellationException("cancelled before run dispatch")
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().job.cancel(cancellation)
                owner.run(f.command)
            }
            caller.join()
            assertTrue(caller.isCancelled)
            val result = assertNotNull(f.owner().inspect(f.command.ref))
            assertNull(result.exitCode)
            assertEquals("Проверка отменена до запуска", result.blockedReason)
            assertTrue(f.driver.prepared.isEmpty())
            assertTrue(f.driver.released.isEmpty())
        }
    }

    @Test fun resetAndCloseWaitForTheRegisteredCallBeforeReadingAnEmptyJournal() = runBlocking<Unit> {
        for (close in listOf(false, true)) Fixture().use { f ->
            val registered = CompletableDeferred<Unit>()
            val releaseDiscovery = CompletableDeferred<Unit>()
            val firstDiscovery = AtomicBoolean(true)
            val events = object : EventJournal by f.events {
                override suspend fun streams(): List<String> {
                    if (firstDiscovery.compareAndSet(true, false)) {
                        registered.complete(Unit)
                        // Pause after registration and before the first target journal lock.
                        withContext(NonCancellable) { releaseDiscovery.await() }
                    }
                    return f.events.streams()
                }
            }
            val owner = f.owner(events)
            val caller = async { owner.run(f.command) }
            registered.await()
            val stopping = async(start = CoroutineStart.UNDISPATCHED) {
                if (close) owner.close() else owner.prepareForReset()
                assertNotNull(f.owner().inspect(f.command.ref)?.blockedReason,
                    "Persistence may be cleared only after the cancelled call's positive receipt is durable")
            }
            try {
                assertNull(withTimeoutOrNull(250) { stopping.await(); true },
                    "Lifecycle must wait for the admitted caller even while its journal is still empty")
                assertTrue(f.driver.prepared.isEmpty())
            } finally { releaseDiscovery.complete(Unit) }
            withTimeout(5_000) { stopping.await(); caller.join() }
            assertFailsWith<CancellationException> { caller.await() }
            assertTrue(f.driver.released.isEmpty())
        }
    }

    @Test fun probeCancellationIsPrimaryAndItsFailedReceiptCannotBeUsedAsProof() = runBlocking<Unit> {
        Fixture().use { f ->
            val cancellation = CancellationException("controlled cancellation")
            f.driver.probePath = f.probe
            f.driver.probeFailure = cancellation
            val events = object : EventJournal by f.events {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                    if ("PreparationRejected" in f.input(detail)) throw IOException("controlled rejection write failure")
                    return f.events.append(expected, operation, at, detail)
                }
            }
            val owner = f.owner(events)
            val caught = assertFailsWith<CancellationException> { owner.run(f.command) }
            val chain = generateSequence<Throwable>(caught) { it.cause }.toList()
            assertTrue(chain.any { it === cancellation })
            assertTrue(chain.any { it.suppressedExceptions.any { it is CheckOutcomeUnknown } })
            assertFailsWith<CheckOutcomeUnknown> { owner.unresolved(f.workspace) }
            assertNull(f.owner().inspect(f.command.ref), "No append is not a positive no-dispatch receipt")
            assertTrue(f.driver.prepared.isEmpty())
        }
    }

    @Test fun exactLostRejectionAcknowledgementReopensAsBlockedWithoutAnyNativeDispatch() = runBlocking<Unit> {
        Fixture().use { f ->
            f.driver.probePath = f.probe
            f.driver.probeFailure = IOException("controlled probe refusal")
            var lost = false
            val events = object : EventJournal by f.events {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                    val value = f.events.append(expected, operation, at, detail)
                    if (!lost && "PreparationRejected" in f.input(detail)) {
                        lost = true
                        throw IOException("controlled lost acknowledgement")
                    }
                    return value
                }
            }
            val owner = f.owner(events)
            assertFailsWith<IOException> { owner.run(f.command) }
            assertTrue(lost)
            val result = assertNotNull(owner.inspect(f.command.ref))
            assertNull(result.exitCode)
            assertNotNull(result.blockedReason)
            assertEquals(result, f.owner().run(f.command))
            assertTrue(owner.unresolved(f.workspace).isEmpty())
            assertEquals(1, f.driver.probes)
            assertTrue(f.driver.prepared.isEmpty())
        }
    }

    @Test fun uncertainParentSubmitCannotBeReclassifiedAsPreparationRejection() = runBlocking<Unit> {
        Fixture().use { f ->
            val events = object : EventJournal by f.events {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                    throw IOException("controlled parent admission write failure")
                }
            }
            val owner = f.owner(events)
            assertFailsWith<CheckOutcomeUnknown> { owner.run(f.command) }
            assertTrue(f.payloads.keys("check-input:").none { "PreparationRejected" in f.payloads.read(it).orEmpty() })
            assertFailsWith<CheckOutcomeUnknown> { owner.unresolved(f.workspace) }
            assertTrue(f.driver.prepared.isEmpty())
        }
    }

    @Test fun parentNoDispatchNeverClearsAnUnknownMetadataChildOrItsAffectedResources() = runBlocking<Unit> {
        Fixture().use { f ->
            f.driver.metadata = true
            f.driver.nativeFailure = IOException("controlled unknown metadata native preparation")
            val command = f.command.copy(affectedResources = setOf(f.sibling))
            assertFailsWith<CheckOutcomeUnknown> { f.owner().run(command) }
            val child = f.driver.prepared.single()
            assertEquals(command.ref, child.metadataSource?.parent)
            val reopened = f.owner()
            val result = assertNotNull(reopened.inspect(command.ref))
            assertNull(result.exitCode)
            assertNotNull(result.blockedReason)
            assertEquals(setOf(child.ref), reopened.unresolved(f.workspace))
            assertEquals(setOf(child.ref), reopened.unresolved(f.sibling))
            assertFailsWith<CheckRejected> { reopened.run(command.copy(ref = command.ref.copy(callId = "fresh"))) }
            assertEquals(listOf(child), f.driver.prepared)
            assertTrue(f.driver.released.isEmpty())
        }
    }

    @Test fun crossResourceRefusalHasItsOwnReceiptAndPreservesTheConflictingUnknownCheck() = runBlocking<Unit> {
        Fixture().use { f ->
            f.driver.nativeFailure = IOException("controlled unknown native preparation")
            val old = f.command.copy(affectedResources = setOf(f.sibling))
            val owner = f.owner()
            assertFailsWith<CheckOutcomeUnknown> { owner.run(old) }
            val blocked = f.command.copy(ref = f.command.ref.copy(callId = "sibling"), workspace = f.sibling)
            assertFailsWith<CheckRejected> { owner.run(blocked) }
            assertNotNull(owner.inspect(blocked.ref)?.blockedReason)
            assertEquals(setOf(old.ref), owner.unresolved(f.sibling))
            assertEquals(listOf(old), f.driver.prepared)
        }
    }
}
