package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.checks.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class OwnedGitReadTest {
    @Test fun readQueriesNeedNoRecursiveMetadataAndReplayAndResourceInspectionHaveNoNativeEffects() = runBlocking<Unit> {
        val root = Files.createTempDirectory("owned-git-read")
        val events = InMemoryEventJournal(); val payloads = InMemoryKeyValueStore(); val driver = Driver()
        val command = command(root.toString())
        val owner = DefaultCommandChecks(events, payloads, driver)
        assertTrue(owner.unresolved(root.toString()).isEmpty())
        assertEquals(0, driver.prepared)
        assertEquals(0, owner.run(command).exitCode)
        assertEquals(1, driver.prepared)
        assertTrue(owner.unresolved(root.toString()).isEmpty())
        val restored = DefaultCommandChecks(events, payloads, driver)
        assertEquals(0, restored.run(command).exitCode)
        assertTrue(restored.unresolved(root.toString()).isEmpty())
        assertEquals(1, driver.prepared)
        assertContentEquals(byteArrayOf(), restored.readOutput(command.ref))
        root.toFile().deleteRecursively()
    }

    @Test fun unknownReadHasExactPersistedResourceAndBlocksFreshReadAfterReopen() = runBlocking<Unit> {
        val root = Files.createTempDirectory("owned-git-read-unknown")
        val cwd = Files.createDirectory(root.resolve("child"))
        val events = InMemoryEventJournal(); val payloads = InMemoryKeyValueStore(); val driver = Driver().apply { unknown = true }
        val command = command(cwd.toString()).copy(protectedResource = root.toString())
        val owner = DefaultCommandChecks(events, payloads, driver)
        assertFailsWith<CheckOutcomeUnknown> { owner.run(command) }
        val restored = DefaultCommandChecks(events, payloads, driver)
        assertEquals(setOf(command.ref), restored.unresolved(root.toString()))
        assertEquals(setOf(command.ref), restored.unresolved(cwd.toString()), "The actual cwd is fenced too")
        assertFailsWith<CheckRejected> { restored.run(command.copy(ref = command.ref.copy(callId = "fresh"))) }
        assertFailsWith<CheckRejected> { restored.run(command.copy(ref = command.ref.copy(callId = "different-primary"), protectedResource = cwd.toString())) }
        assertEquals(1, driver.prepared)
        root.toFile().deleteRecursively()
    }

    private fun command(path: String) = CheckCommand(CheckRef(CheckScope("p", "s", "r", 0), "read"), path,
        CheckGitReadQuery.ROOT.arguments(), policy = CheckPolicy.GIT_READ_ONLY, outputMode = CheckOutputMode.BINARY_STDOUT)
    private class Driver : CheckProcessDriver {
        var prepared = 0
        var unknown = false
        override suspend fun needsGitMetadata(command: CheckCommand): Boolean = error("Fixed reads cannot recurse into metadata preparation")
        override suspend fun readOutput(output: CheckOutputRef) = byteArrayOf()
        override suspend fun prepare(command: CheckCommand, receiptId: String, authority: CheckAuthorityRecorder): PreparedCommandCheck {
            prepared++
            return object : PreparedCommandCheck {
                override val receipt = CheckProcessReceipt(receiptId, "controlled", 1)
                override suspend fun release() = Unit
                override suspend fun awaitResult(progress: (String) -> Unit) = CheckResult("", 0,
                    binaryOutput = CheckOutputRef(receiptId, 0, "a".repeat(64)))
                override suspend fun stopAndConfirm(): CheckCleanup {
                    if (unknown) throw IOException("Controlled missing group proof")
                    return CheckCleanup("group", "authority")
                }
                override suspend fun attest() = "artifact"
                override suspend fun discard() = Unit
            }
        }
    }
}
