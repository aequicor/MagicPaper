package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class OwnedGitMetadataTest {
    private class Driver : CheckProcessDriver {
        val prepared = mutableListOf<CheckCommand>()
        val released = mutableListOf<CheckCommand>()
        val metadataGrants = CompletableDeferred<Unit>()
        var unknownMetadata = false
        var waitMetadata = false
        var failAfterMetadata = false
        var onGrant: (CheckCommand) -> Unit = {}
        override suspend fun needsGitMetadata(command: CheckCommand) = true
        override suspend fun prepare(command: CheckCommand, receiptId: String, authority: CheckAuthorityRecorder) =
            prepareWithMetadata(command, receiptId, authority, null)

        override suspend fun prepareWithMetadata(command: CheckCommand, receiptId: String, authority: CheckAuthorityRecorder,
            metadata: CheckGitMetadata?): PreparedCommandCheck {
            if (command.metadataSource == null) {
                assertEquals(command.ref, assertNotNull(metadata).parent)
                assertEquals(command.resource, metadata.protectedResource)
                assertEquals(CheckGitMetadataQuery.entries.toSet(), metadata.outputs.keys)
                if (failAfterMetadata) throw CheckNotDispatched("Controlled preflight refusal")
            } else assertNull(metadata)
            prepared += command
            return object : PreparedCommandCheck {
                override val receipt = CheckProcessReceipt(receiptId, "controlled-group", 123)
                override suspend fun release() {
                    onGrant(command)
                    released += command
                    if (command.metadataSource != null) metadataGrants.complete(Unit)
                }
                override suspend fun awaitResult(progress: (String) -> Unit): CheckResult {
                    if (waitMetadata && command.metadataSource != null) awaitCancellation()
                    return CheckResult("", 0, binaryOutput = if (command.metadataSource == null) null
                        else CheckOutputRef(receiptId, 0, "a".repeat(64)))
                }
                override suspend fun stopAndConfirm(): CheckCleanup {
                    if (unknownMetadata && command.metadataSource != null) throw IOException("Controlled missing group proof")
                    return CheckCleanup("group", "authority")
                }
                override suspend fun attest() = "artifact"
                override suspend fun discard() = Unit
            }
        }
    }
    private class Fixture {
        val workspace = Files.createTempDirectory("owned-metadata-").toRealPath()
        val events = InMemoryEventJournal()
        val payloads = InMemoryKeyValueStore()
        val driver = Driver()
        val command = CheckCommand(CheckRef(CheckScope("project", "session", "request", 0), "parent"), workspace.toString(), listOf("build"))
        fun owner(events: EventJournal = this.events) = DefaultCommandChecks(events, payloads, driver)
        fun dispose() { workspace.toFile().deleteRecursively() }
    }

    @Test fun metadataHasExactParentResourceAndDurableReleaseBeforeEveryNativeGrant() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.driver.onGrant = { command ->
                val inputs = f.payloads.keys("check-input:").map { f.payloads.read(it).orEmpty() }
                assertTrue(inputs.any { "Release" in it && command.ref.callId in it })
                val source = command.metadataSource
                if (source != null) {
                    assertEquals(f.command.ref, source.parent)
                    assertEquals(f.command.workspace, command.protectedResource)
                    assertTrue(f.driver.released.none { it.metadataSource == null })
                }
            }
            assertEquals(0, f.owner().run(f.command).exitCode)
            assertEquals(CheckGitMetadataQuery.entries, f.driver.released.mapNotNull { it.metadataSource?.query })
            assertEquals(f.command, f.driver.released.last())
            assertEquals(0, f.owner().run(f.command).exitCode)
            assertEquals(4, f.driver.released.size, "Completed parent replay must run neither metadata nor the parent again")
        } finally { f.dispose() }
    }

    @Test fun unknownMetadataBlocksParentAndFreshRequestsAfterRestoreWithoutEffects() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.driver.unknownMetadata = true
            assertFailsWith<CheckOutcomeUnknown> { f.owner().run(f.command) }
            val child = f.driver.prepared.single()
            assertEquals(f.command.ref, child.metadataSource?.parent)
            val reopened = f.owner()
            assertNull(reopened.inspect(f.command.ref))
            assertFailsWith<CheckOutcomeUnknown> { reopened.reconcile(f.command.ref.scope.sessionId) }
            assertFailsWith<CheckRejected> { reopened.run(f.command.copy(ref = f.command.ref.copy(callId = "fresh-parent"))) }
            assertEquals(listOf(child), f.driver.prepared)
            assertEquals(listOf(child), f.driver.released)
        } finally { f.dispose() }
    }

    @Test fun lostMetadataReleaseAcknowledgementDoesNotRepeatItsNativeGrant() = runBlocking<Unit> {
        val f = Fixture()
        try {
            var count = 0
            val lostAck = object : EventJournal by f.events {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                    val written = f.events.append(expected, operation, at, detail)
                    if (++count == 3) throw IOException("Controlled lost acknowledgement")
                    return written
                }
            }
            assertEquals(0, f.owner(lostAck).run(f.command).exitCode)
            assertEquals(4, f.driver.released.size)
            assertEquals(4, f.driver.released.map { it.ref }.distinct().size)
            assertEquals(0, f.owner().run(f.command).exitCode)
            assertEquals(4, f.driver.released.size)
        } finally { f.dispose() }
    }

    @Test fun unconfirmedMetadataReleaseJournalWritePreventsNativeGrantAndFencesRestart() = runBlocking<Unit> {
        val f = Fixture()
        try {
            var count = 0
            val broken = object : EventJournal by f.events {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                    if (++count >= 3) throw IOException("Controlled release persistence failure")
                    return f.events.append(expected, operation, at, detail)
                }
            }
            assertFailsWith<CheckOutcomeUnknown> { f.owner(broken).run(f.command) }
            assertEquals(1, f.driver.prepared.size)
            assertTrue(f.driver.prepared.all { it.metadataSource?.parent == f.command.ref })
            assertTrue(f.driver.released.isEmpty())
            val reopened = f.owner()
            assertFailsWith<CheckRejected> { reopened.run(f.command.copy(ref = f.command.ref.copy(callId = "fresh"))) }
            assertTrue(f.driver.released.isEmpty())
            assertEquals(1, f.driver.prepared.size)
        } finally { f.dispose() }
    }

    @Test fun cancellationDuringMetadataNeverDispatchesParentAndSafeRetryReadsFreshValues() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.driver.waitMetadata = true
            val owner = f.owner()
            val running = async { owner.run(f.command) }
            f.driver.metadataGrants.await()
            running.cancel(CancellationException("Controlled metadata cancellation"))
            assertFailsWith<CancellationException> { running.await() }
            running.join()
            assertTrue(f.driver.released.all { it.metadataSource != null })
            val old = f.driver.released.single().ref
            f.driver.waitMetadata = false
            val reopened = f.owner()
            val cancelled = reopened.run(f.command)
            assertNull(cancelled.exitCode)
            assertNotNull(cancelled.blockedReason)
            val fresh = f.command.copy(ref = f.command.ref.copy(callId = "fresh-parent"))
            assertEquals(0, reopened.run(fresh).exitCode)
            assertEquals(4, f.driver.released.count { it.metadataSource != null })
            assertEquals(1, f.driver.released.count { it.metadataSource == null })
            assertEquals(1, f.driver.released.count { it.ref == old })
        } finally { f.dispose() }
    }
}
