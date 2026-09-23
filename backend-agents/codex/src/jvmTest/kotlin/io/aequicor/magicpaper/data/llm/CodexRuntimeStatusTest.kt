package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.NativeDiagnostics
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Every start without Codex logged «Не найден Codex app-server» as an ERROR with a full stack, although not installing
 * Codex is a choice rather than a failure. The status names what to install and the log records it once; a Codex that
 * is there but cannot start is still a failure.
 */
class CodexRuntimeStatusTest {
    private class Recorded : NativeDiagnostics {
        val errors = mutableListOf<String>()
        val infos = mutableListOf<Pair<String, Map<String, String>>>()
        override fun error(component: String, event: String, cause: Throwable, fields: Map<String, String>) { errors += event }
        override fun info(component: String, event: String, fields: Map<String, String>) { infos += event to fields }
    }

    @Test fun absentCodexIsReportedAsNotInstalledWithoutAnError() = runBlocking {
        val home = Files.createTempDirectory("codex-status-")
        try {
            val diagnostics = Recorded()
            val status = nativeTestClient(Json, home, diagnostics, command = "magicpaper-absent-codex-${UUID.randomUUID()}").runtimeStatus()
            assertFalse(status.ready)
            assertEquals("Не найден Codex app-server. Установите Codex desktop/CLI или задайте MAGICPAPER_CODEX_PATH.", status.detail)
            assertEquals(emptyList(), diagnostics.errors)
            assertEquals(listOf("runtime_status" to mapOf("result" to "not_installed")), diagnostics.infos)
        } finally { home.toFile().deleteRecursively() }
    }

    @Test fun codexThatIsThereButCannotStartIsStillAnError() = runBlocking {
        val home = Files.createTempDirectory("codex-status-")
        try {
            val broken = Files.writeString(home.resolve("codex"), "not a program")
            val diagnostics = Recorded()
            val status = nativeTestClient(Json, home, diagnostics, command = broken.toString()).runtimeStatus()
            assertFalse(status.ready)
            assertEquals(listOf("runtime_status_failed"), diagnostics.errors)
            assertTrue(diagnostics.infos.isEmpty())
        } finally { home.toFile().deleteRecursively() }
    }
}
