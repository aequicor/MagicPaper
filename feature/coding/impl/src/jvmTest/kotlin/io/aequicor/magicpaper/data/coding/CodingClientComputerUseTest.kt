package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.computer.DesktopComputerUse
import io.aequicor.magicpaper.data.computer.FakeComputerDesktop
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class CodingClientComputerUseTest {
    @Test fun closingTurnClientPreservesAnotherSessionsComputerGrant() = runBlocking {
        val root = Files.createTempDirectory("magicpaper-client-computer")
        val computer = DesktopComputerUse(FakeComputerDesktop())
        val owner = CodexAppServerOpenAiSubscription(Json, root, computerUse = computer)
        try {
            computer.enable("other", ComputerAccess.SCREEN)
            val grant = computer.grant("other")!!
            val client = owner.newCodingClient()
            assertSame(computer, client.computerUse)
            client.close()
            assertEquals(grant, computer.grant("other"))
            owner.close()
            assertNull(computer.grant("other"))
        } finally { owner.close(); root.toFile().deleteRecursively() }
    }

    @Test fun failedPreflightReleasesOnlyItsOwnGrant() = runBlocking {
        val root = Files.createTempDirectory("magicpaper-runtime-computer")
        val computer = DesktopComputerUse(FakeComputerDesktop())
        val owner = CodexAppServerOpenAiSubscription(Json, root, computerUse = computer)
        val runtime = DesktopCodingRuntime(PiCodingRuntime(root.resolve("pi").toFile(), computerUse = computer), owner)
        try {
            assertSame(computer, runtime.computerUse)
            computer.enable("owner", ComputerAccess.SCREEN)
            val grant = computer.grant("owner")!!
            val project = CodingProject("p", "Project", root.toString(), 1)
            val unconfigured = LlmProfile("invalid", "Missing model")
            suspend fun run(id: String) = runtime.run(project, CodingSession(id, "p", id, 1, engine = CodingEngine.CODEX), "test", unconfigured).toList()
            assertTrue(run("other").any { it is CodingEvent.Failed })
            assertEquals(grant, computer.grant("owner"))
            assertTrue(run("owner").any { it is CodingEvent.Failed })
            assertNull(computer.grant("owner"))
        } finally { owner.close(); root.toFile().deleteRecursively() }
    }
}
