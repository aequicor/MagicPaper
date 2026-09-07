package io.aequicor.magicpaper.data.coding

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real child process test, no network/provider credentials involved. */
class CodingProcessLifetimeTest {
    private fun helper(vararg args: String = arrayOf("parent")): Process {
        val cp = listOf(ProcessLifetimeFixture::class.java.protectionDomain.codeSource.location,
            OwnedCodingProcess::class.java.protectionDomain.codeSource.location,
            com.sun.jna.Native::class.java.protectionDomain.codeSource.location,
            Unit::class.java.protectionDomain.codeSource.location).joinToString(File.pathSeparator) { File(it.toURI()).path }
        val java = File(System.getProperty("java.home"), "bin/java").path
        return ProcessBuilder(listOf(java, "-cp", cp, ProcessLifetimeFixture::class.java.name) + args).start()
    }
    @Test fun windowsKillsChildToolsWhenTheirParentExits() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val parent = helper()
        var child: ProcessHandle? = null
        try {
            CodingProcessLifetime.attach(parent)
            parent.outputStream.write(10); parent.outputStream.flush()
            val pid = parent.inputStream.bufferedReader().readLine().toLong()
            child = ProcessHandle.of(pid).orElseThrow()
            assertTrue(child.isAlive)
            parent.destroyForcibly(); parent.waitFor(10, TimeUnit.SECONDS)
            child.onExit().get(10, TimeUnit.SECONDS)
            assertFalse(child.isAlive)
        } finally { parent.destroyForcibly(); child?.destroyForcibly() }
    }
    @Test fun durableOwnershipReconcilesOnlyRecordedProcess() {
        val parent = helper()
        try {
            val ownership = OwnedCodingProcess(Files.createTempDirectory("planning-process-").toFile())
            ownership.record("session", parent)
            assertTrue(ownership.belongsTo("session", parent))
            ownership.reconcile("session")
            assertFalse(parent.isAlive)
            ownership.reconcile("session") // idempotent
        } finally { parent.destroyForcibly() }
    }

    @Test fun recoveryAfterOwnerCrashTerminatesAgentAndChildTool() {
        val directory = Files.createTempDirectory("planning-owner-crash-").toFile()
        val owner = helper("owner", directory.path)
        var agent: ProcessHandle? = null
        var tool: ProcessHandle? = null
        try {
            val line = java.util.concurrent.CompletableFuture.supplyAsync {
                owner.inputStream.bufferedReader().readLine()
            }.get(10, TimeUnit.SECONDS)
            if (line == null) fail("Owner fixture failed: ${owner.errorStream.bufferedReader().readText()}")
            val pids = line.split(" ").map(String::toLong)
            agent = ProcessHandle.of(pids[0]).orElseThrow()
            tool = ProcessHandle.of(pids[1]).orElseThrow()
            assertTrue(agent.isAlive && tool.isAlive)
            owner.destroyForcibly()
            assertTrue(owner.waitFor(10, TimeUnit.SECONDS))
            OwnedCodingProcess(directory).reconcile("session")
            assertFalse(agent.isAlive)
            assertFalse(tool.isAlive)
            assertFalse(File(directory, "session.process").exists())
        } finally {
            owner.destroyForcibly(); agent?.destroyForcibly(); tool?.destroyForcibly()
            directory.deleteRecursively()
        }
    }
}

object ProcessLifetimeFixture {
    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "child") { Thread.sleep(30_000); return }
        if (args.firstOrNull() == "owner") {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val agent = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ProcessLifetimeFixture::class.java.name, "parent").start()
            OwnedCodingProcess(File(args[1])).record("session", agent)
            agent.outputStream.write(10); agent.outputStream.flush()
            val childPid = agent.inputStream.bufferedReader().readLine()
            println("${agent.pid()} $childPid"); System.out.flush()
            Thread.sleep(30_000)
            return
        }
        System.`in`.read()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ProcessLifetimeFixture::class.java.name, "child").start()
        println(child.pid()); System.out.flush()
        Thread.sleep(30_000)
    }
}
