package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.data.coding.OwnedCodingProcess
import io.aequicor.magicpaper.data.coding.ProcessLifetimeFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Exercises protocol completion and durable ownership using an idle local process, no provider. */
class CodexCodingRecoveryTest {
    private fun helper(): Process {
        val cp = listOf(ProcessLifetimeFixture::class.java.protectionDomain.codeSource.location,
            Unit::class.java.protectionDomain.codeSource.location).joinToString(File.pathSeparator) { File(it.toURI()).path }
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", cp, ProcessLifetimeFixture::class.java.name, "parent").start()
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun accumulator(): Any = Class.forName("io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription\$CodingAccumulator")
        .getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    @Suppress("UNCHECKED_CAST")
    @Test fun confirmedInterruptedTurnReleasesOnlyItsOwnSession() = runBlocking {
        val home = Files.createTempDirectory("codex-recovery-test-")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        val process = helper()
        try {
            field(service, "process").set(service, process)
            val ownership = OwnedCodingProcess(home.resolve("coding-processes").toFile())
            ownership.record("session", process)
            val sessions = field(service, "codingSessions").get(service) as MutableMap<String, String>
            val runs = field(service, "codingRuns").get(service) as MutableMap<String, Any>
            sessions["session"] = "thread"; sessions["other-session"] = "other-thread"
            runs["thread"] = accumulator(); runs["other-thread"] = accumulator()
            service.javaClass.getDeclaredMethod("handleNotification", String::class.java, JsonObject::class.java).apply { isAccessible = true }
                .invoke(service, "turn/completed", buildJsonObject { put("threadId", "thread"); putJsonObject("turn") { put("id", "turn") } })
            service.reconcileCoding("session")
            assertTrue(process.isAlive, "The shared app-server must stay alive")
            assertFalse(ownership.belongsTo("session", process))
            assertEquals("other-thread", sessions["other-session"])
            assertTrue("other-thread" in runs)
        } finally { service.close(); process.destroyForcibly() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun brokenConnectionDoesNotConfirmThatExecutorStopped() = runBlocking {
        val home = Files.createTempDirectory("codex-disconnect-test-")
        val service = CodexAppServerOpenAiSubscription(Json, home)
        val process = helper()
        try {
            field(service, "process").set(service, process)
            val ownership = OwnedCodingProcess(home.resolve("coding-processes").toFile())
            ownership.record("session", process)
            (field(service, "codingSessions").get(service) as MutableMap<String, String>)["session"] = "thread"
            val run = accumulator()
            (field(service, "codingRuns").get(service) as MutableMap<String, Any>)["thread"] = run
            service.javaClass.getDeclaredMethod("failAll", String::class.java).apply { isAccessible = true }.invoke(service, "connection lost")
            assertFalse(field(run, "confirmed").getBoolean(run))
            assertFails { service.reconcileCoding("session") }
            assertTrue(ownership.belongsTo("session", process))
            assertTrue(process.isAlive)
        } finally { service.close(); process.destroyForcibly() }
    }
}
