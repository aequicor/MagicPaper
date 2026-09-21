package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.CodexCompletionRequest
import io.aequicor.magicpaper.backend.NativeDiagnostics
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringWriter
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class CodexNativeFailureTest {
    @Test fun failedLimitReadKeepsKnownAccountAndReportsUnavailable() = runBlocking {
        RpcFixture { method ->
            when (method) {
                "account/read" -> account()
                "account/rateLimits/read" -> throw IOException("fixture transport failed")
                else -> error(method)
            }
        }.use { fixture ->
            val account = fixture.client.account()
            assertTrue(account.signedIn)
            assertEquals("fixture-plan", account.planType)
            assertTrue(account.rateLimitsUnavailable)
            assertTrue(account.rateLimits.isEmpty())
            assertEquals(listOf("rate_limits_read_failed"), fixture.failures.map { it.first })
        }
    }

    @Test fun cancelledLimitReadDoesNotProduceAnAccountSnapshot() = runBlocking {
        RpcFixture { method ->
            if (method == "account/read") account() else throw CancellationException("fixture cancellation")
        }.use { fixture ->
            assertFailsWith<CancellationException> { fixture.client.account() }
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test fun cancellationAttemptsBothCleanupOperationsAndRetainsCancellation() = runBlocking {
        val turnStarted = CompletableDeferred<Unit>()
        RpcFixture { method ->
            when (method) {
                "account/read" -> account()
                "account/rateLimits/read" -> buildJsonObject { putJsonObject("rateLimits") {} }
                "thread/start" -> buildJsonObject { putJsonObject("thread") { put("id", "thread") } }
                "turn/start" -> buildJsonObject { putJsonObject("turn") { put("id", "turn") } }
                    .also { turnStarted.complete(Unit) }
                "turn/interrupt", "thread/unsubscribe" -> throw IOException("fixture cleanup failed")
                else -> error(method)
            }
        }.use { fixture ->
            val completion = async {
                fixture.client.complete(CodexCompletionRequest("model", "", "", JsonArray(emptyList()), null, 0), {}, {})
            }
            withTimeout(5_000) { turnStarted.await() }
            // Wait until the start response was consumed, so cancellation exercises interrupt too.
            withTimeout(5_000) { while (fixture.pendingCount() != 0) kotlinx.coroutines.yield() }
            completion.cancelAndJoin()
            assertTrue(completion.isCancelled)
            assertEquals(listOf("turn/interrupt", "thread/unsubscribe"),
                fixture.failures.map { it.second["operation"] })
            assertTrue(fixture.failures.all { it.second["result"] == "unknown" })
        }
    }

    private fun account() = buildJsonObject {
        putJsonObject("account") { put("type", "chatgpt"); put("planType", "fixture-plan") }
    }

    /** Drives the real RPC request path without a provider or an installed native agent. */
    private class RpcFixture(val respond: (String) -> JsonElement) : AutoCloseable {
        private val home = Files.createTempDirectory("codex-rpc-failure")
        val failures = mutableListOf<Pair<String, Map<String, String>>>()
        val client = nativeTestClient(Json, home, NativeDiagnostics { _, event, _, fields -> failures += event to fields })
        private fun field(name: String) = client.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private val pending = field("pending").get(client) as MutableMap<Long, CompletableDeferred<JsonElement>>
        fun pendingCount() = pending.size
        init {
            field("process").set(client, object : Process() {
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() = Unit
                override fun isAlive() = true
            })
            val sink = StringWriter()
            field("writer").set(client, object : BufferedWriter(sink) {
                override fun flush() {
                    super.flush()
                    val message = Json.parseToJsonElement(sink.toString().trim()).jsonObject
                    sink.buffer.setLength(0)
                    val result = respond(message.getValue("method").jsonPrimitive.content)
                    pending.getValue(message.getValue("id").jsonPrimitive.long).complete(result)
                }
            })
        }
        override fun close() {
            field("process").set(client, null)
            client.close()
            home.toFile().deleteRecursively()
        }
    }
}
