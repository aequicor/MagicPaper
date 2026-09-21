package io.aequicor.magicpaper.data.layout

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopLayoutEditorIntegrationTest {
    @Test fun chatAuthoringOpensPackagedEditorCreatesDocumentAndReturnsRaster() = runBlocking {
        assumeTrue(System.getProperty("magicpaper.paperEditor.it") == "true")
        val binary = Path.of(System.getProperty("magicpaper.paperEditor.testExecutable"))
        assertTrue(Files.isExecutable(binary), "Build the PaperEditor distribution first")
        val root = Files.createTempDirectory("paper layout project ")
        val editor = DesktopLayoutEditor(executable = { binary }, stateDirectory = root.resolve("editor-state"))
        val generated = DesktopLayoutEditor.EmptyLayout.replace("Новый макет", "Вход в кабинет")
        try {
            val agent = LayoutChatAgent(object : LlmGateway {
                override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                    assertTrue(messages.first().content.contains("PaperButton"))
                    return generated
                }
            }, editor)
            val result = agent.answer(CodingProject("fixture", "Fixture", root.toString(), 0), "chat", "request",
                "Создай макет входа", emptyList(), LlmProfile("local", "Fixture", baseUrl = "http://unused", modelId = "fixture"), emptyList())
            val preview = assertNotNull(result.attachments.singleOrNull(), result.text)
            assertTrue(preview.sizeBytes > 1000)
            assertEquals(generated, Files.readString(root.resolve("design/layouts/chat-chat/screen.layout.md")))
            val report = Path.of("build/reports/layout-chat/generated.png")
            Files.createDirectories(report.parent); Files.write(report, preview.bytes)
            val next = editor.open(CodingProject("fixture", "Fixture", root.toString(), 0), "chat")
            assertEquals(generated, next.source, "Follow-up reads the current document")
        } finally {
            // Only this test worker's child editor with this temporary project can be stopped.
            ProcessHandle.current().children().use { children -> children.filter { process ->
                process.info().arguments().orElse(emptyArray()).any { it.startsWith(root.toString()) }
            }.forEach { process -> process.destroy(); if (process.isAlive) process.destroyForcibly() } }
            root.toFile().deleteRecursively()
        }
    }
}
