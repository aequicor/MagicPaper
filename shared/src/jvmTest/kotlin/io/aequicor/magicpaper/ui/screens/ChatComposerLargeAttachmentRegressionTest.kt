package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.AttachmentKind
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class ChatComposerLargeAttachmentRegressionTest {
    @Test fun imageOnlySendKeepsSameNamedPayloadsWhenOversizedPreviewFails() {
        var picked: ((List<Attachment>) -> Unit)? = null
        val sent = mutableListOf<List<Attachment>>()
        ImageComposeScene(680, 220) { MagicPaperTheme { Surface {
            Composer(enabled = true, session = ChatSession("one", "One", 1, 1), profiles = emptyList(), activeProfileId = "",
                onSend = { _, attachments -> sent += attachments }, onOpenSwitcher = {},
                onPickAttachments = { _, onPicked -> picked = onPicked })
        } } }.use { scene ->
            fun nodes(): List<SemanticsNode> {
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            }
            fun render(frame: Long) { scene.render(frame).close() }
            render(16_000_000L)
            val attach = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "📎" } }
            scene.sendPointerEvent(PointerEventType.Press, attach.boundsInRoot.center)
            scene.sendPointerEvent(PointerEventType.Release, attach.boundsInRoot.center)
            render(32_000_000L)
            val oversizedPayload = "A".repeat(5_600_000)
            picked!!.invoke(listOf(
                Attachment("same", "same.png", "image/png", 3, "YWJj", AttachmentKind.IMAGE),
                Attachment("same", "same.png", "image/png", 3, "ZGVm", AttachmentKind.IMAGE),
                Attachment("large", "same.png", "image/png", 4_200_000, oversizedPayload, AttachmentKind.IMAGE),
            ))
            repeat(8) { render((it + 3) * 16_000_000L); Thread.sleep(15) }
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { label -> label.contains("Миниатюра недоступна") } == true })
            val send = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Отправить") }
            assertTrue(send.config[SemanticsActions.OnClick].action?.invoke() == true)
            assertEquals(listOf(listOf("YWJj", "ZGVm", oversizedPayload)), sent.map { it.map(Attachment::dataBase64) })
        }
    }
}
