package io.aequicor.magicpaper.data.computer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

class NativeApplicationDesktopTest {
    private class Host(response: String = "", private val blockWrite: Boolean = false) : Process() {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        @Volatile var destroyed = false
        var failDestroy = false
        private val input = ByteArrayInputStream(response.toByteArray())
        private val output = object : OutputStream() {
            override fun write(b: Int) {
                entered.complete(Unit)
                if (blockWrite) released.await()
            }
        }
        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { released.await(); return 0 }
        override fun exitValue(): Int { check(destroyed); return 0 }
        override fun destroy() { destroyed = true; released.countDown() }
        override fun destroyForcibly(): Process { check(!failDestroy) { "Cleanup failed" }; destroy(); return this }
    }
    private fun inspect() = buildJsonObject { put("action", "inspect"); put("window_id", "foreign") }

    @Test fun cancellationWhilePipeWriteIsBlockedKillsHostWithoutWaitingForProvider() = runBlocking {
        val process = Host(blockWrite = true)
        val native = NativeApplicationDesktop(launchProcess = { process })
        try {
            val job = launch(Dispatchers.IO) {
                val context = currentCoroutineContext()
                native.request(inspect()) { context.ensureActive() }
            }
            withTimeout(3_000) { process.entered.await(); job.cancelAndJoin() }
            assertTrue(process.destroyed)
        } finally { native.close() }
    }

    @Test fun malformedEofAndOversizeResponsesCloseHostAndNeverExposeNativePayload() {
        for (response in listOf("", "private provider body\n", "[]\n", "x".repeat(8 * 1024 * 1024 + 2))) {
            val process = Host(response)
            NativeApplicationDesktop(launchProcess = { process }).use { native ->
                val failure = assertFailsWith<ApplicationAdapterException> { native.request(inspect()) {} }
                assertFalse(failure.message.orEmpty().contains("private provider body"))
                assertTrue(process.destroyed)
            }
        }
        NativeApplicationDesktop(launchProcess = { Host("{\"error\":\"private provider body\"}\n") }).use { native ->
            assertFalse(assertFailsWith<ApplicationAdapterException> { native.request(inspect()) {} }.message.orEmpty().contains("private provider body"))
        }
    }

    @Test fun cleanupFailurePreservesTheOriginalOperationOrCancellationAndCanBeRetried() {
        for (cancel in listOf(false, true)) {
            val primary = if (cancel) CancellationException("Cancelled") else IllegalStateException("private native body")
            val process = Host().apply { failDestroy = true }
            val native = NativeApplicationDesktop(launchProcess = { process })
            var checks = 0
            try {
                val failure = assertFails { native.request(inspect()) { if (++checks == 2) throw primary } }
                if (cancel) assertSame(primary, failure)
                else {
                    assertIs<ApplicationAdapterException>(failure)
                    assertSame(primary, failure.cause)
                    assertFalse(failure.message.orEmpty().contains("private native body"))
                }
                assertEquals("Cleanup failed", primary.suppressedExceptions.single().message)
            } finally { process.failDestroy = false; native.close() }
            assertTrue(process.destroyed)
        }
    }

    @Test fun macPreflightReportsPermissionsWithoutRequestingThem() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac") && NativeApplicationDesktop.supported)
        val directory = Files.createTempDirectory("application-permissions-smoke-")
        try {
            NativeApplicationDesktop(directory).use { native ->
                val result = native.request(request("permissions")) {}
                assertEquals(setOf("accessibility", "screen_capture"), result.keys)
                result.values.forEach { assertNotNull(it.jsonPrimitive.booleanOrNull) }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun bundledHostRejectsForeignReferenceWithoutReadingDesktopOrPromptingForPermissions() {
        assumeTrue(NativeApplicationDesktop.supported)
        val directory = Files.createTempDirectory("application-adapter-smoke-")
        try {
            NativeApplicationDesktop(directory).use { native ->
                val failure = assertFailsWith<ApplicationAdapterException> { native.request(inspect()) {} }
                assertContains(failure.message.orEmpty(), "Окно или элемент изменились")
                // Two responses establish framing and a retained host, not merely successful launch.
                assertContains(assertFailsWith<ApplicationAdapterException> { native.request(inspect()) {} }.message.orEmpty(), "Окно или элемент изменились")
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
