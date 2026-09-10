package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.data.coding.OwnedCodingProcess
import io.aequicor.magicpaper.data.coding.ProcessLifetimeFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Local process trees only: no Codex installation, provider, network, or actual task commands. */
class CodexBackgroundProcessRecoveryTest {
    private fun helper(): Process {
        val cp = listOf(ProcessLifetimeFixture::class.java.protectionDomain.codeSource.location,
            Unit::class.java.protectionDomain.codeSource.location).joinToString(File.pathSeparator) { File(it.toURI()).path }
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", cp,
            ProcessLifetimeFixture::class.java.name, "parent").start()
    }
    private fun child(parent: Process): ProcessHandle {
        parent.outputStream.write(10); parent.outputStream.flush()
        val pid = CompletableFuture.supplyAsync { parent.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)
        return ProcessHandle.of(pid.toLong()).orElseThrow()
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun accumulator(): Any = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
        .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    private fun notify(service: CodexAppServerOpenAiSubscription, method: String, params: JsonObject) {
        service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java)
            .apply { isAccessible = true }.invoke(service, method, params)
    }
    @Suppress("UNCHECKED_CAST")
    private fun deferred(service: CodexAppServerOpenAiSubscription): Any {
        val run = accumulator()
        (field(service, "codingSessions").get(service) as MutableMap<String, String>)["session"] = "thread"
        (field(service, "codingRuns").get(service) as MutableMap<String, Any>)["thread"] = run
        notify(service, "item/started", buildJsonObject {
            put("threadId", "thread")
            putJsonObject("item") { put("id", "background-command"); put("type", "commandExecution"); put("command", "local fixture"); put("status", "inProgress") }
        })
        notify(service, "turn/completed", buildJsonObject {
            put("threadId", "thread"); putJsonObject("turn") { put("id", "finished-turn"); put("status", "completed") }
        })
        assertFalse(field(run, "closed").getBoolean(run))
        return run
    }

    @Test fun closingClientStopsCapturedChildBeforeLosingItsParent() {
        val home = Files.createTempDirectory("codex-close-children-")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        val parent = helper(); var tool: ProcessHandle? = null
        try {
            tool = child(parent)
            field(service, "process").set(service, parent)
            service.close()
            assertFalse(parent.isAlive)
            assertFalse(tool.isAlive, "The child must not survive the app-server it belonged to")
        } finally { service.close(); parent.destroyForcibly(); tool?.destroyForcibly(); home.toFile().deleteRecursively() }
    }

    @Test fun deferredCompletedTurnReconcilesItsOwnedProcessTreeWithoutClaimingToolSuccess() = runBlocking {
        val home = Files.createTempDirectory("codex-background-recovery-")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        val parent = helper(); var tool: ProcessHandle? = null
        try {
            tool = child(parent)
            field(service, "process").set(service, parent)
            val ownership = OwnedCodingProcess(home.resolve("coding-processes").toFile())
            ownership.record("session", parent)
            val run = deferred(service)
            service.reconcileCoding("session")
            assertFalse(parent.isAlive)
            assertFalse(tool.isAlive)
            assertFalse(ownership.belongsTo("session", parent))
            assertFalse(field(run, "confirmed").getBoolean(run), "Process cleanup must not confirm the unknown tool result")
        } finally { service.close(); parent.destroyForcibly(); tool?.destroyForcibly(); home.toFile().deleteRecursively() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun deferredCommandCannotKillSharedProcessUsedByAnotherTask() = runBlocking {
        val home = Files.createTempDirectory("codex-shared-background-")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        val parent = helper(); var tool: ProcessHandle? = null
        try {
            tool = child(parent)
            field(service, "process").set(service, parent)
            val ownership = OwnedCodingProcess(home.resolve("coding-processes").toFile())
            ownership.record("session", parent)
            deferred(service)
            (field(service, "codingRuns").get(service) as MutableMap<String, Any>)["other-thread"] = accumulator()
            assertFailsWith<IllegalStateException> { service.reconcileCoding("session") }
            assertTrue(parent.isAlive && tool.isAlive)
            assertTrue(ownership.belongsTo("session", parent))
        } finally { service.close(); parent.destroyForcibly(); tool?.destroyForcibly(); home.toFile().deleteRecursively() }
    }
}
