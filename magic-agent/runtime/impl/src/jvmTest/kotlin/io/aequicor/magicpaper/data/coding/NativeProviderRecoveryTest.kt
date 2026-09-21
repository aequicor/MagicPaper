package io.aequicor.magicpaper.data.coding

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class NativeProviderRecoveryTest {
    @Test fun restoredNamespaceSkipsLiveOwnersAndRemovesDeadOwnerReceiptsWithoutTrustingPidAlone() {
        val root = Files.createTempDirectory("native-provider-recovery").toFile()
        try {
            val current = ProcessHandle.current()
            val started = current.info().startInstant().orElseThrow().toEpochMilli()
            val live = File(root, "live.process").apply { writeText("${current.pid()}\n$started\n${current.pid()}\n$started") }
            // Deliberately use this application's PID but a different start time. It must never be killed.
            val stale = File(root, "stale.process").apply { writeText("${current.pid()}\n${started - 1}\n${current.pid()}\n${started - 1}") }
            OwnedCodingProcess(root).reconcileOrphans()
            assertTrue(live.isFile)
            assertFalse(stale.exists())
            assertTrue(current.isAlive)
        } finally { root.deleteRecursively() }
    }
    @Test fun exactProcessOfADeadOwnerIsTerminatedBeforeItsReceiptIsRemoved() {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        val node = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
        org.junit.Assume.assumeTrue("Offline process recovery fixture uses local Node", node != null)
        val root = Files.createTempDirectory("native-provider-orphan").toFile()
        val process = ProcessBuilder(checkNotNull(node).absolutePath, "-e", "setInterval(() => {},1000)").start()
        try {
            val owner = ProcessHandle.current()
            val receipt = File(root, "orphan.process").apply { writeText(listOf(process.pid(),
                process.info().startInstant().orElseThrow().toEpochMilli(), owner.pid(),
                owner.info().startInstant().orElseThrow().toEpochMilli() - 1).joinToString("\n")) }
            OwnedCodingProcess(root).reconcileOrphans()
            assertFalse(process.isAlive)
            assertFalse(receipt.exists())
        } finally { if (process.isAlive) process.destroyForcibly(); root.deleteRecursively() }
    }
    @Test fun unreadableOwnershipIsVisibleAndCannotBeClearedAsSuccessfulRecovery() {
        val root = Files.createTempDirectory("native-provider-recovery").toFile()
        try {
            val invalid = File(root, "unknown.process").apply { writeText("truncated receipt") }
            assertFailsWith<IllegalArgumentException> { OwnedCodingProcess(root).reconcileOrphans() }
            assertTrue(invalid.exists())
        } finally { root.deleteRecursively() }
    }
}
