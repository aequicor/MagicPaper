package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.nativeRunBlocking

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** Offline contract for the streamSimple(...).result() export in @earendil-works/pi-ai 0.84.4. */
class PiProviderTurnExecutionTest {
    @Test fun pinnedInstalledLibraryUsesExactExportAndPreservesResponsesContinuation() {
        val library = System.getenv("MAGICPAPER_PI_AI_DIST")
        assumeTrue("Installed pinned library contract is opt-in and uses no network", library != null)
        val root = Files.createTempDirectory("pi-provider-pinned-").toFile()
        try {
            val adapter = root.resolve("provider-turn.mjs")
            val check = root.resolve("check.mjs")
            adapter.writeBytes(javaClass.getResourceAsStream("/coding/provider-turn.mjs")!!.use { it.readBytes() })
            check.writeBytes(javaClass.getResourceAsStream("/coding/provider-turn-pinned-contract.mjs")!!.use { it.readBytes() })
            val process = ProcessBuilder(node(), check.absolutePath, checkNotNull(library), adapter.absolutePath)
                .redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally { root.deleteRecursively() }
    }
    private class Ownership : NativeProcessOwnership {
        val recorded = mutableMapOf<String, Process>()
        val cleared = mutableListOf<String>()
        override fun record(id: String, process: Process, attachLifetime: Boolean) { recorded[id] = process }
        override fun clear(id: String) { cleared += id }
    }
    private val usage = "{input:12,output:3,cacheRead:2,cacheWrite:0,totalTokens:17,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}"
    private fun node(): String {
        val names = if (System.getProperty("os.name").startsWith("Windows")) listOf("node.exe", "node") else listOf("node")
        val node = System.getenv("PATH").orEmpty().split(File.pathSeparator).flatMap { dir -> names.map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("Offline adapter contract requires a local Node; no runtime is downloaded", node != null)
        return checkNotNull(node).absolutePath
    }
    private fun fixture(root: File, body: String): PiProviderTurnRequest {
        val library = root.resolve("pi-ai-0.84.4/dist")
        library.resolve("api").mkdirs()
        root.resolve("pi-ai-0.84.4/package.json").writeText("""{"name":"@earendil-works/pi-ai","version":"0.84.4","type":"module"}""")
        library.resolve("api/openai-codex-responses.js").writeText("""
            import assert from 'node:assert/strict';
            import {writeFileSync} from 'node:fs';
            import {spawn} from 'node:child_process';
            export function streamSimple(model, context, options) {
              assert.equal(options.apiKey, 'fixture-token');
              assert.equal(model.provider, 'openai-codex');
              assert.equal(options.transport, 'sse');
              assert.equal(options.maxRetries, 0);
              return { result: async () => { $body } };
            }
        """.trimIndent())
        val script = root.resolve("provider-turn.mjs").apply {
            writeBytes(checkNotNull(PiProviderTurnExecutionTest::class.java.getResourceAsStream("/coding/provider-turn.mjs")).use { it.readBytes() })
        }
        return PiProviderTurnRequest(
            LlmProfile("subscription", "Subscription", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "gpt-5.4"),
            listOf(LlmMessage(LlmChatRole.SYSTEM, "System"), LlmMessage(LlmChatRole.USER, "Question")),
            listOf(LlmToolDefinition("search", "Search", buildJsonObject { put("type", "object") })), emptyList(),
            node(), library.absolutePath, script.absolutePath, "fixture-token")
    }

    @Test fun returnsCallsWithoutExecutingAndContinuesWithExactSignedAssistant() = nativeRunBlocking<Unit> {
        val root = Files.createTempDirectory("pi-provider-turn-").toFile()
        try {
            val request = fixture(root, """
                const previous = context.messages.find(m => m.role === 'toolResult');
                const common = {role:'assistant',api:model.api,provider:model.provider,model:model.id,usage:$usage,timestamp:42};
                if (previous) {
                  assert.equal(previous.toolCallId, 'call_123|fc_456');
                  assert.equal(previous.toolName, 'search');
                  assert.equal(previous.content[0].text, 'common executor result');
                  const assistant = context.messages.find(m => m.role === 'assistant');
                  assert.equal(assistant.content[0].thinkingSignature, 'opaque signed thinking');
                  return {...common,content:[{type:'text',text:'Final answer'}],stopReason:'stop'};
                }
                assert.equal(context.tools[0].name, 'search');
                return {...common,stopReason:'toolUse',content:[{type:'thinking',thinking:'',thinkingSignature:'opaque signed thinking'},
                  {type:'toolCall',id:'call_123|fc_456',name:'search',arguments:{query:'question'}}]};
            """.trimIndent())
            val owner = Ownership()
            val calls = mutableListOf<UsageCallResult>()
            PiProviderTurnExecution(owner, NativeDiagnostics { _, _, cause, _ -> fail(cause.toString()) }).use { provider ->
                val first = provider.turn(request, calls::add)
                assertEquals("call_123|fc_456", first.calls.single().id)
                val result = LlmToolResult(first.calls.single().id, "search", JsonPrimitive("common executor result"))
                val second = provider.turn(request.copy(exchanges = listOf(LlmToolExchange(first, listOf(result)))), calls::add)
                assertEquals("Final answer", second.text)
                assertTrue(second.calls.isEmpty())
            }
            assertEquals(listOf(17L, 17L), calls.map { it.tokens.totalTokens })
            assertEquals(listOf(1L, 1L), calls.map { it.requests })
            assertEquals(owner.recorded.keys, owner.cleared.toSet())
            assertTrue(owner.recorded.values.none { it.isAlive })
            assertFalse(root.walkTopDown().filter { it.isFile && it.name != "openai-codex-responses.js" }.any { it.readText().contains("fixture-token") })
        } finally { root.deleteRecursively() }
    }

    @Test fun providerFailureNeverLeaksItsMessageAndStillRecordsKnownUsage() = nativeRunBlocking<Unit> {
        val root = Files.createTempDirectory("pi-provider-failed-").toFile()
        try {
            val request = fixture(root, "return {stopReason:'error',errorMessage:'fixture-token private-request',usage:$usage};")
            val logs = mutableListOf<String>()
            val usages = mutableListOf<UsageCallResult>()
            PiProviderTurnExecution(Ownership(), NativeDiagnostics { _, _, cause, fields -> logs += cause.stackTraceToString() + fields }).use { provider ->
                val failure = assertFailsWith<IllegalStateException> { provider.turn(request, usages::add) }
                assertFalse(failure.stackTraceToString().contains("fixture-token"))
                assertFalse(failure.stackTraceToString().contains("private-request"))
            }
            assertEquals(1, usages.size)
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { "fixture-token" in it || "private-request" in it })
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationKillsChildTreeBeforeClearingOwnership() = nativeRunBlocking<Unit> {
        val root = Files.createTempDirectory("pi-provider-cancel-").toFile()
        try {
            val pidFile = root.resolve("child-pid")
            val request = fixture(root, """
                const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], {stdio:'ignore'});
                writeFileSync(${JsonPrimitive(pidFile.absolutePath)}, String(child.pid));
                await new Promise(resolve => setTimeout(resolve, 60000));
            """.trimIndent())
            val owner = Ownership()
            PiProviderTurnExecution(owner, NativeDiagnostics { _, _, cause, _ -> fail(cause.toString()) }).use { provider ->
                val running = async { provider.turn(request) { fail("Cancelled request has no observed usage") } }
                withTimeout(10_000) { while (!pidFile.isFile || pidFile.readText().isBlank()) delay(10) }
                val childPid = pidFile.readText().toLong()
                withTimeout(5_000) { running.cancelAndJoin() }
                assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))
                assertTrue(owner.recorded.values.none { it.isAlive })
                assertEquals(owner.recorded.keys, owner.cleared.toSet())
            }
        } finally { root.deleteRecursively() }
    }
}
