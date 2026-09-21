package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class PiProcessExecutionTest {
    private suspend fun PiNativeAdapter.execution(owner: NativeProcessOwnership): PiNativeExecution {
        val context = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[NativeAttemptContext])
        return execution(owner, context.events, context.events.admitLaunch(context.run))
    }
    private class Owner(private val receipt: File) : NativeProcessOwnership {
        var child: Process? = null
        var cleared = false
        override fun record(id: String, process: Process, attachLifetime: Boolean) {
            assertEquals("session", id)
            child = process
            receipt.writeText(process.pid().toString())
        }
        override fun clear(id: String) {
            assertFalse(checkNotNull(child).isAlive, "Ownership must outlive the native process")
            cleared = true
            receipt.delete()
        }
    }
    private fun request(root: File, source: String, prompt: String = "text"): PiExecutionRequest {
        val probe = File(root, "NativeProbe.java").apply { writeText("""
            class NativeProbe {
                public static void main(String[] args) throws Exception {
                    $source
                }
            }
        """.trimIndent()) }
        val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        return PiExecutionRequest("session", prompt, PiLaunchRequest(java.path, probe.path, root.path,
            "model", root.resolve("sessions").path, root.resolve("system.md").path, false, emptyList(), null,
            null, null, mapOf("RECEIPT" to root.resolve("owner").path), emptySet()))
    }

    @Test fun journalRejectsDeliveryBeforeTheProcessCanReceiveUserInput() = runBlocking {
        val root = Files.createTempDirectory("pi-journal-fence-").toFile()
        val ownership = Owner(root.resolve("owner"))
        val backing = MemoryNativeJournal()
        val journal = object : NativeLifecycleJournal by backing {
            override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? {
                if (entry.input is NativeLifecycleMachine.Fact.DeliveryRequested) error("fixture journal unavailable")
                return backing.append(expected, entry)
            }
        }
        val lifecycle = testNativeLifecycle(journal)
        val run = NativeRunRef("session", "fenced")
        try {
            lifecycle.begin(run, null)
            val attempt = lifecycle.admitLaunch(run)
            val input = request(root, """
                if (System.in.readAllBytes().length > 0) java.nio.file.Files.writeString(
                    java.nio.file.Path.of(System.getenv("RECEIPT") + ".delivered"), "unexpected");
            """.trimIndent())
            val result = withTimeout(20_000) { PiNativeAdapter().execution(ownership, lifecycle, attempt).run(input) {} }
            assertNotNull(result.cause)
            assertFalse(root.resolve("owner.delivered").exists())
            assertFalse(checkNotNull(ownership.child).isAlive)
            val saved = lifecycle.inspect().items.single()
            assertEquals(NativeOutcome.NOT_DISPATCHED, saved.outcome)
            assertEquals(NativeTermination.STOPPED, saved.termination)
        } finally { ownership.child?.destroyForcibly(); root.deleteRecursively() }
    }

    @Test fun lostJournalAcknowledgementStillDeliversExactlyOnce() = runBlocking {
        val root = Files.createTempDirectory("pi-journal-ack-").toFile()
        val ownership = Owner(root.resolve("owner"))
        val backing = MemoryNativeJournal()
        val journal = object : NativeLifecycleJournal by backing {
            override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? {
                val result = backing.append(expected, entry)
                if (entry.input is NativeLifecycleMachine.Fact.DeliveryRequested) error("fixture lost acknowledgement")
                return result
            }
        }
        val lifecycle = testNativeLifecycle(journal)
        val run = NativeRunRef("session", "once")
        try {
            lifecycle.begin(run, null)
            val attempt = lifecycle.admitLaunch(run)
            val lines = mutableListOf<String>()
            val result = withTimeout(20_000) { PiNativeAdapter().execution(ownership, lifecycle, attempt)
                .run(request(root, "System.out.println(new String(System.in.readAllBytes()));"), lines::add) }
            assertEquals(0, result.exitCode)
            assertEquals(listOf("text"), lines)
            assertEquals(1, backing.snapshot().entries.count { it.input is NativeLifecycleMachine.Fact.DeliveryRequested })
            // A successful exit by itself still cannot claim a completed native agent action.
            assertEquals(NativeOutcome.UNKNOWN, lifecycle.inspect().items.single().outcome)
        } finally { ownership.child?.destroyForcibly(); root.deleteRecursively() }
    }

    @Test fun recordsOwnershipBeforeSendingPromptAndDecodesDamagedUtf8() = nativeRunBlocking {
        val root = Files.createTempDirectory("pi-native-stream-").toFile()
        try {
            val owner = Owner(root.resolve("owner"))
            val request = request(root, """
                byte[] prompt = System.in.readAllBytes();
                if (!java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("RECEIPT")))) throw new Exception("missing ownership");
                System.out.println(new String(prompt, java.nio.charset.StandardCharsets.UTF_8));
                System.out.write(new byte[] { (byte)0xff, 10 }); System.out.flush();
            """.trimIndent(), "Привет \"quoted\" world")
            val lines = mutableListOf<String>()
            val result = withTimeout(20_000) { PiNativeAdapter().execution(owner).run(request, lines::add) }
            assertEquals(0, result.exitCode, result.stderr)
            assertNull(result.launchError)
            assertEquals(listOf(request.prompt, "\uFFFD"), lines)
            assertTrue(owner.cleared)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationStopsSilentChildAndKeepsCancellationAsControlFlow() = nativeRunBlocking {
        val root = Files.createTempDirectory("pi-native-cancel-").toFile()
        val owner = Owner(root.resolve("owner"))
        try {
            val ready = CompletableDeferred<Unit>()
            val execution = PiNativeAdapter().execution(owner)
            val run = async { execution.run(request(root, "System.in.readAllBytes(); System.out.println(\"ready\"); System.out.flush(); Thread.sleep(60000);")) { ready.complete(Unit) } }
            withTimeout(20_000) { ready.await() }
            withTimeout(5_000) { run.cancelAndJoin() }
            assertTrue(run.isCancelled)
            assertFalse(checkNotNull(owner.child).isAlive)
            assertTrue(owner.cleared)
        } finally { owner.child?.destroyForcibly(); root.deleteRecursively() }
    }

    @Test fun explicitAbortStopsNativeProcessWithoutConvertingItToSuccessfulCompletion() = nativeRunBlocking {
        val root = Files.createTempDirectory("pi-native-abort-").toFile()
        val owner = Owner(root.resolve("owner"))
        try {
            val ready = CompletableDeferred<Unit>()
            val execution = PiNativeAdapter().execution(owner)
            val run = async { execution.run(request(root, "System.in.readAllBytes(); System.out.println(\"ready\"); System.out.flush(); Thread.sleep(60000);")) { ready.complete(Unit) } }
            withTimeout(20_000) { ready.await() }
            execution.abort()
            val result = withTimeout(5_000) { run.await() }
            assertTrue(result.aborted)
            assertTrue(owner.cleared)
        } finally { owner.child?.destroyForcibly(); root.deleteRecursively() }
    }

    @Test fun consumerFailureCannotLeaveReaderAndChildRunning() = nativeRunBlocking {
        val root = Files.createTempDirectory("pi-native-consumer-").toFile()
        val owner = Owner(root.resolve("owner"))
        try {
            val failure = assertFailsWith<IllegalStateException> {
                withTimeout(20_000) {
                    PiNativeAdapter().execution(owner).run(request(root, "System.in.readAllBytes(); System.out.println(\"ready\"); System.out.flush(); Thread.sleep(60000);")) {
                        error("consumer failed")
                    }
                }
            }
            assertEquals("consumer failed", failure.message)
            assertFalse(checkNotNull(owner.child).isAlive)
            assertTrue(owner.cleared)
        } finally { owner.child?.destroyForcibly(); root.deleteRecursively() }
    }


    @Test fun failedOwnershipCleanupCannotReportSuccessfulExecution() = nativeRunBlocking {
        val root = Files.createTempDirectory("pi-native-owner-cleanup-").toFile()
        var child: Process? = null
        try {
            val ownership = object : NativeProcessOwnership {
                override fun record(id: String, process: Process, attachLifetime: Boolean) { child = process }
                override fun clear(id: String) { error("receipt could not be cleared") }
            }
            val failure = assertFailsWith<IllegalStateException> {
                withTimeout(20_000) {
                    PiNativeAdapter().execution(ownership).run(request(root, "System.in.readAllBytes();")) {}
                }
            }
            assertEquals("receipt could not be cleared", failure.message)
            assertFalse(checkNotNull(child).isAlive)
        } finally { child?.destroyForcibly(); root.deleteRecursively() }
    }

    @Test fun ownershipFailureStopsChildAndPreservesTheOriginalCause() = nativeRunBlocking {
        val root = Files.createTempDirectory("pi-native-owner-failure-").toFile()
        var child: Process? = null
        val expected = java.io.IOException("receipt write failed")
        try {
            val ownership = object : NativeProcessOwnership {
                override fun record(id: String, process: Process, attachLifetime: Boolean) {
                    child = process
                    throw expected
                }
                override fun clear(id: String) { assertFalse(checkNotNull(child).isAlive) }
            }
            val result = withTimeout(20_000) {
                PiNativeAdapter().execution(ownership).run(request(root, "System.in.readAllBytes(); Thread.sleep(60000);")) {}
            }
            assertSame(expected, result.cause)
            assertNull(result.exitCode)
            assertFalse(checkNotNull(child).isAlive)
        } finally { child?.destroyForcibly(); root.deleteRecursively() }
    }

    @Test fun abortBeforeStartNeverLaunchesProcessOrWritesOwnership() = nativeRunBlocking {
        val root = Files.createTempDirectory("pi-native-early-abort-").toFile()
        try {
            val owner = Owner(root.resolve("owner"))
            val execution = PiNativeAdapter().execution(owner)
            execution.abort()
            assertTrue(execution.run(request(root, "throw new Exception(\"must not start\");")) {}.aborted)
            assertNull(owner.child)
            assertFalse(owner.cleared)
        } finally { root.deleteRecursively() }
    }
}
