package io.aequicor.magicpaper.tools.editor

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PaperAgentCommandsTest {
    @Test fun catalogueAndPaperLayoutRenderUseActualPlugin() = runBlocking {
        val catalog = paperAgentCommand("catalog", null, null)
        assertEquals(54, catalog["components"]!!.jsonArray.size)
        val output = Files.createTempFile("paper-agent-", ".png")
        try {
            val result = paperAgentCommand("render", Path.of("examples/paper-workspace.layout.md"), output)
            assertTrue(result["valid"]!!.jsonPrimitive.boolean, result.toString())
            assertEquals(960, result["width"]!!.jsonPrimitive.int)
            assertTrue(Files.size(output) > 1000)
        } finally { Files.deleteIfExists(output) }
    }
    @Test fun unsupportedComponentDoesNotProduceSuccessfulPreview() = runBlocking {
        val input = Files.createTempFile("bad-paper-", ".layout.md")
        val output = Files.createTempFile("bad-paper-", ".png")
        try {
            Files.writeString(input, Files.readString(Path.of("examples/paper-workspace.layout.md")).replace("of PaperButton", "of MissingButton"))
            val result = paperAgentCommand("render", input, output)
            assertFalse(result["valid"]!!.jsonPrimitive.boolean)
            assertTrue(result["diagnostics"]!!.jsonPrimitive.content.contains("MissingButton"))
            assertEquals(0L, Files.size(output))
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output) }
    }
}
