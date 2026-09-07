package io.aequicor.magicpaper.data.coding

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real child process test, no network/provider credentials involved. */
class CodingProcessLifetimeTest {
    private fun helper(): Process {
        val cp = listOf(ProcessLifetimeFixture::class.java.protectionDomain.codeSource.location,
            Unit::class.java.protectionDomain.codeSource.location).joinToString(File.pathSeparator) { File(it.toURI()).path }
        val java = File(System.getProperty("java.home"), "bin/java").path
        return ProcessBuilder(java, "-cp", cp, ProcessLifetimeFixture::class.java.name, "parent").start()
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
}

object ProcessLifetimeFixture {
    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "child") { Thread.sleep(30_000); return }
        System.`in`.read()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ProcessLifetimeFixture::class.java.name, "child").start()
        println(child.pid()); System.out.flush()
        Thread.sleep(30_000)
    }
}
