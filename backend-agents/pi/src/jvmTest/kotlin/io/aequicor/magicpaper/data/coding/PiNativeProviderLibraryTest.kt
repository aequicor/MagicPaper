package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import org.junit.Assume.assumeTrue

class PiNativeProviderLibraryTest {
    @Test fun materializationPreservesResourcePathAndContentAddressedIdentity() {
        val root = Files.createTempDirectory("provider-resources").toFile()
        try {
            PiNativeProviderLibrary(root, NativeResources { name -> javaClass.getResourceAsStream(name)?.use { it.readBytes() } },
                Ownership(), diagnostics, testNativeLifecycle()).use { library ->
                val first = library.script("usage-context.mjs")
                val expected = javaClass.getResourceAsStream("/coding/usage-context.mjs")!!.use { it.readBytes() }
                assertContentEquals(expected, first.readBytes())
                assertEquals(first, library.script("usage-context.mjs"))
                assertEquals("adapters", first.parentFile.parentFile.name)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun bridgeOwnsProcessBeforeStdinAndPublishesMetricsWithoutAnyUsageService() = runBlocking {
        val root = Files.createTempDirectory("provider-bridge").toFile()
        val owner = Ownership()
        val source = """
            process.stdin.once('data', () => {
              console.log(JSON.stringify({port: 12345}));
              console.log(JSON.stringify({type:'magicpaper_usage',id:'turn-1',phase:'completed',usage:{input:4,output:2}}));
            });
            setInterval(() => {}, 1000);
        """.trimIndent()
        try {
            val library = PiNativeProviderLibrary(root, NativeResources { source.toByteArray() }, owner, diagnostics, testNativeLifecycle(), Installation(node()))
            library.use {
                val bridge = library.bridge(profile, JsonObject(emptyMap()))
                assertEquals(1, owner.processes.size)
                val metric = withTimeout(5_000) { bridge.usage.first() }
                assertEquals("turn-1", metric.id)
                assertTrue(metric.completed)
                assertEquals(6L, metric.usage.tokens.totalTokens)
                assertFalse(bridge.connection.toString().contains(bridge.connection.bearerToken))
                bridge.shutdown(); bridge.shutdown(); bridge.close()
                assertFalse(owner.processes.single().isAlive)
                assertEquals(1, owner.cleared)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun cancelledStartupTerminatesPipeReaderAndRetainsCancellation() = runBlocking {
        val root = Files.createTempDirectory("provider-bridge-cancel").toFile()
        val owner = Ownership()
        try {
            PiNativeProviderLibrary(root, NativeResources { "setInterval(() => {},1000);".toByteArray() }, owner,
                diagnostics, testNativeLifecycle(), Installation(node())).use { library ->
                val job = async { library.bridge(profile, JsonObject(emptyMap())) }
                withTimeout(5_000) { while (owner.processes.isEmpty()) delay(10) }
                job.cancel()
                withTimeout(5_000) { assertFailsWith<CancellationException> { job.await() }; job.join() }
                assertFalse(owner.processes.single().isAlive)
                assertEquals(1, owner.cleared)
            }
        } finally { root.deleteRecursively() }
    }
    private val profile = LlmProfile("local", "Local", modelId = "fixture", baseUrl = "http://127.0.0.1:1", apiKey = "fixture")
    private val diagnostics = NativeDiagnostics { _, _, cause, _ -> throw AssertionError(cause) }
    private class Ownership : NativeProviderProcesses {
        var restores = 0
        override fun reconcileOrphans() { restores++ }
        val processes = java.util.concurrent.CopyOnWriteArrayList<Process>()
        var cleared = 0
        override fun record(id: String, process: Process, attachLifetime: Boolean) {
            assertEquals(1, restores, "Recovery must complete before launching native model access")
            processes += process
        }
        override fun clear(id: String) { cleared++ }
    }
    private class Installation(private val executable: String) : PiInstallation {
        override val cliPath = "unused"
        override fun aiDirectory() = "unused"
        override suspend fun status() = NativeInstallationStatus(NativeInstallationPhase.READY, "Ready")
        override fun ensureReady() = flow { emit(status()) }
        override suspend fun uninstall() = error("Unexpected installation")
        override suspend fun node() = executable
        override fun prepareBundledTools() = error("Unexpected agent preparation")
        override fun toolsNotice() = ""
        override fun ensureFuzzySafety() = error("Unexpected agent preparation")
        override fun bashPath(): String? = null
        override fun homeDefaults(directory: String) = error("Unexpected agent preparation")
        override fun environment(nodePath: String, home: String) = emptyMap<String, String>()
    }
    private fun node(): String {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        val executable = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("Local Node fixture requires no downloads", executable != null)
        return checkNotNull(executable).absolutePath
    }
}
