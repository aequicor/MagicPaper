package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import kotlin.test.*

class CodexProcessLifecycleTest {
    @Test fun receiptFailureBeforeAttachedStillRequiresCleanupAndDisconnectRetainsHandle() = runBlocking {
        processFixture { client, child, events ->
            assertFailsWith<IllegalStateException> {
                withContext(NativeAttemptContext(events.ref.run, events)) { client.runCoding(session, request(), false).toList() }
            }
            assertTrue(child.isAlive)
            assertEquals(listOf("launch", "stopping"), events.observed)
            // A stdout disconnect clears the current connection, not the retained cleanup handle.
            field(client, "process").set(client, null)
            client.shutdownCoding()
            assertFalse(child.isAlive)
            assertEquals(listOf("launch", "stopping", "stopped"), events.observed)
        }
    }

    @Test fun deadParentAloneCannotProduceStoppedProof() = runBlocking {
        processFixture { client, child, events ->
            assertFailsWith<IllegalStateException> {
                withContext(NativeAttemptContext(events.ref.run, events)) { client.runCoding(session, request(), false).toList() }
            }
            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { client.shutdownCoding() }
            assertFalse("stopped" in events.observed)
        }
    }

    private val session = CodingSession("session", "project", "", 0, engine = CodingEngine.CODEX)
    private fun request() = CodexRunRequest(".", "fixture", "fixture", JsonObject(emptyMap()), JsonObject(emptyMap()),
        "", JsonArray(emptyList()), CodingInteractionMode.CODE, null, null, "fixture", null)
    private fun field(client: CodexNativeClient, name: String) = client.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private suspend fun processFixture(test: suspend (CodexNativeClient, Process, Events) -> Unit) {
        val home = Files.createTempDirectory("codex-lifecycle")
        val source = home.resolve("PassiveChild.java")
        Files.writeString(source, """
            class PassiveChild {
                public static void main(String[] args) throws Exception {
                    System.out.println("ready"); System.out.flush();
                    Thread.sleep(60000);
                }
            }
        """.trimIndent())
        val child = ProcessBuilder(System.getProperty("java.home") + "/bin/java", source.toString()).start()
        val client = nativeTestClient(Json, home)
        try {
            assertEquals("ready", withContext(Dispatchers.IO) { child.inputStream.bufferedReader().readLine() })
            field(client, "process").set(client, child)
            field(client, "writer").set(client, child.outputStream.bufferedWriter())
            @Suppress("UNCHECKED_CAST")
            val handles = field(client, "launchedProcesses").get(client) as MutableSet<Process>
            handles.add(child)
            test(client, child, Events(child))
        } finally {
            child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS)
            // The test owns this emergency cleanup; it deliberately does not manufacture a stopped fact.
            field(client, "process").set(client, null)
            @Suppress("UNCHECKED_CAST")
            (field(client, "launchedProcesses").get(client) as MutableSet<Process>).clear()
            client.close()
            home.toFile().deleteRecursively()
        }
    }
    private class Events(val process: Process) : NativeAttemptEvents {
        val ref = NativeAttemptRef(NativeRunRef("session", "request"), 0)
        val observed = mutableListOf<String>()
        override suspend fun admitLaunch(run: NativeRunRef) = ref.also { observed += "launch" }
        override suspend fun attached(attempt: NativeAttemptRef, process: NativeProcessIdentity) = error("Receipt failed first")
        override suspend fun deliver(attempt: NativeAttemptRef, stage: NativeDelivery) = error("No request may be sent")
        override suspend fun accepted(attempt: NativeAttemptRef, nativeThreadId: String?, nativeTurnId: String?) = error("No native response")
        override suspend fun terminal(attempt: NativeAttemptRef, outcome: NativeOutcome) = error("No terminal outcome")
        override suspend fun stopping(attempt: NativeAttemptRef) { observed += "stopping" }
        override suspend fun stopped(attempt: NativeAttemptRef) { assertFalse(process.isAlive); observed += "stopped" }
        override suspend fun unavailable(attempt: NativeAttemptRef) = error("Created process requires cleanup")
    }
}
