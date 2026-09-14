package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class CodingImageAttachmentRenderTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLJqQAAAABJRU5ErkJggg=="

    @Test fun inputsResultsPreviewAndUnavailableSourcesRemainSeparated() {
        fun input(id: String, name: String, locator: CodingImageLocator) = CodingImageReference(
            id, CodingImageSource.USER_ATTACHMENT, "session", "run", "timeline", "user",
            name = name, mimeType = "image/png", sizeBytes = 70, locator = locator,
        )
        val usableInput = input("input", "input.png", CodingImageLocator.InlineBase64(png))
        val removed = input("removed", "removed.png", CodingImageLocator.ManagedBlob("deleted"))
        val denied = input("denied", "denied.png", CodingImageLocator.ManagedBlob("access-denied"))
        val corrupt = input("corrupt", "corrupt.png", CodingImageLocator.InlineBase64("YWJj"))
        val output = CodingImageReference("output", CodingImageSource.TOOL_RESULT, "session", "run", "timeline", "agent",
            callId = "call", name = "result.png", mimeType = "image/png", sizeBytes = 70, locator = CodingImageLocator.InlineBase64(png))
        val user = CodingMessage("user", CodingRole.USER, "look", createdAt = 0,
            images = listOf(usableInput, removed, denied, corrupt, output))
        val step = CodingStep(CodingStepKind.TOOL, "render", callId = "call", images = listOf(usableInput, output))
        val agent = CodingMessage("agent", CodingRole.AGENT, "", createdAt = 0, timelineId = "timeline", steps = listOf(step))
        ImageComposeScene(640, 420) { MagicPaperTheme { Column {
            CodingInputImages(user)
            CodingResultImages(agent, step)
            // An old AttachmentMeta has no trusted bytes and must stay explicitly unavailable.
            CodingAttachments(listOf(AttachmentMeta("legacy.png", "image/png", 70, AttachmentKind.IMAGE)))
        } } }.use { scene ->
            fun render() = repeat(20) { scene.render(it * 16_000_000L).close(); Thread.sleep(5) }
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            fun descriptions() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            fun clickDescription(description: String) {
                val node = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(description) }
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            render()
            val unavailable = descriptions()
            assertTrue(unavailable.any { it.contains("removed.png: источник изображения недоступен") })
            assertTrue(unavailable.any { it.contains("denied.png: источник изображения недоступен") })
            assertTrue(unavailable.any { it.contains("corrupt.png: Миниатюра недоступна") })
            assertTrue(unavailable.any { it.contains("legacy.png: источник изображения недоступен") })
            assertTrue(unavailable.none { it.contains("result.png") && it.contains("input") })

            clickDescription("Открыть input.png")
            render()
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.contains("input.png · 70 Б") } })
        }
    }

    @Test fun exactToolResultOpensItsOwnPreview() {
        val output = CodingImageReference("output", CodingImageSource.TOOL_RESULT, "session", "run", "timeline", "agent",
            callId = "call", name = "result.png", mimeType = "image/png", sizeBytes = 70, locator = CodingImageLocator.InlineBase64(png))
        val step = CodingStep(CodingStepKind.TOOL, "render", callId = "call", images = listOf(output))
        val agent = CodingMessage("agent", CodingRole.AGENT, "", createdAt = 0, timelineId = "timeline", steps = listOf(step))
        ImageComposeScene(500, 220) { MagicPaperTheme { CodingResultImages(agent, step) } }.use { scene ->
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            repeat(20) { scene.render(it * 16_000_000L).close(); Thread.sleep(5) }
            val button = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Открыть result.png") }
            assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
            repeat(20) { scene.render((it + 20) * 16_000_000L).close(); Thread.sleep(5) }
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.contains("result.png · 70 Б") } })
        }
    }
}
