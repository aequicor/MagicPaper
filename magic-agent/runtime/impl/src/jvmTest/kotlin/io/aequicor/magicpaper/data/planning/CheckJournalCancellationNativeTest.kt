package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.checks.createCommandChecks
import io.aequicor.magicpaper.data.storage.DurableByteStore
import io.aequicor.magicpaper.data.storage.FileDurableByteStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.StorageArea
import io.aequicor.magicpaper.data.storage.persistenceStores
import io.aequicor.magicpaper.domain.checks.CheckGitReadQuery
import io.aequicor.magicpaper.domain.checks.CheckPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A project screen cancels its Git reads when the user navigates away. On the desktop store a record
 * write returns through withContext, so a cancelled caller can get CancellationException for a record
 * that is already on disk. The owner then asks the journal whether its record landed; answered from a
 * listing cached before the write it kept appending as if it had not, and the next process refused to
 * replay that history: every Git read, worktree preparation and session start failed with
 * «Завершение проверки не подтверждено» until the stream was removed by hand. This drives the real
 * chain — sandboxed git, the file store, the durable journal — and restarts over the same storage.
 *
 * The window is the whole blocking write, not its last instruction: a cancellation that arrives at any
 * point of it wins when the write returns. Windows flushes each record with FlushFileBuffers for
 * milliseconds; macOS returns from fsync almost at once. [SlowCommit] gives the write that duration,
 * so the defect is reached here as often as on the machine where it was reported.
 * Opt-in like every other native check: `-Pmagicpaper.research.native=true`.
 */
class CheckJournalCancellationNativeTest {
    @Test fun gitReadsCancelledAtAnyPointLeaveAJournalTheNextProcessReplays() = runBlocking<Unit> {
        assumeTrue(System.getProperty("magicpaper.research.native") == "true")
        val root = Files.createTempDirectory("check-journal-cancel-")
        val project = Files.createDirectories(root.resolve("project")).toFile()
        val data = root.resolve("data").toFile()
        val payloads = InMemoryKeyValueStore()
        try {
            git(project, "init", "-q", "-b", "main")
            git(project, "commit", "--allow-empty", "-q", "-m", "base")
            val first = createCommandChecks(persistenceStores(SlowCommit(FileDurableByteStore(data))).events, payloads, root.resolve("checks"))
            val refused = mutableListOf<String>()
            try {
                suspend fun read() = OwnedGitCommands.readOnly(first, project.path)
                    .known(project, CheckGitReadQuery.STATUS.arguments(), CheckPolicy.GIT_READ_ONLY)
                // The first read pays for the sandbox; a cancellation point drawn from it would mostly land after a
                // warm read had already finished, and the test would prove nothing.
                repeat(2) { read() }
                val full = List(5) { System.nanoTime().also { read() }.let { (System.nanoTime() - it) / 1_000_000 } }
                    .sorted()[2].coerceAtLeast(20)
                val random = Random(20260923)
                repeat(ITERATIONS) {
                    val reading = launch(Dispatchers.IO) {
                        try { read() } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            synchronized(refused) { refused += "${failure::class.simpleName}: ${failure.message}" }
                        }
                    }
                    delay(random.nextLong(0, full + 1))
                    reading.cancelAndJoin()
                }
            } finally { first.close() }
            // A cancelled read settles as cancelled; it must not fence the reads that follow it.
            assertEquals(emptyMap(), refused.groupingBy { it }.eachCount(), "reads refused after a cancelled one")
            // A fresh process: a cold journal listing over the same storage, the call a worktree acquisition makes first.
            val second = createCommandChecks(persistenceStores(FileDurableByteStore(data)).events, payloads, root.resolve("checks"))
            try { second.unresolved(project.canonicalPath) }
            catch (failure: Exception) {
                throw AssertionError("The journal the first process wrote no longer replays " +
                    "(in-process refusals: ${refused.groupingBy { it }.eachCount()})", failure)
            } finally { second.close() }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun git(directory: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git") + arguments).directory(directory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }

    /** The record is on disk before the blocking write returns, as after a slow flush on Windows. */
    private class SlowCommit(private val files: DurableByteStore) : DurableByteStore by files {
        override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
            files.write(area, key, bytes)
            Thread.sleep(3)
        }
    }

    private companion object { const val ITERATIONS = 300 }
}
