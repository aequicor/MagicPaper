package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.CodingApprovalDock
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

class CodingApprovalRenderTest {
    private val request = CodingApproval("a", "s", "p", "Сборка проекта", CodingApprovalKind.COMMAND,
        "Для загрузки зависимостей нужен доступ к сети. Разрешить сборку проекта?",
        "Рабочая папка: /projects/MagicPaper\n\n./gradlew :shared:jvmTest")

    @Test fun rendersScrollableDetailsAndOnlyExplicitEnabledButtonSubmits() {
        val pending = mutableStateOf(listOf(request))
        val decisions = mutableListOf<CodingApprovalDecision>()
        var height = 0
        ImageComposeScene(390, 440) {
            MagicPaperTheme {
                CodingApprovalDock(pending.value, { _, decision ->
                    decisions += decision
                    pending.value = pending.value.map { it.copy(submitting = true) }
                }, {}, Modifier.fillMaxWidth().heightIn(max = 420.dp).onSizeChanged { height = it.height })
            }
        }.use { scene ->
            var frame = 0L
            fun render() { repeat(12) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(25) } }
            fun click(x: Float) {
                val y = height - 36f
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                render()
            }
            render()
            assertTrue(decisions.isEmpty(), "Opening the card never grants permission")
            assertTrue(height in 150..420)
            click(260f)
            assertEquals(listOf(CodingApprovalDecision.ALLOW_ONCE), decisions)
            click(60f)
            assertEquals(1, decisions.size, "Buttons are disabled while sending a decision")
            pending.value = listOf(request.copy(id = "next", details = request.details + "\n" + "Подробности операции\n".repeat(100)))
            render()
            assertTrue(height <= 420, "Long details leave the action buttons visible")
            click(60f)
            assertEquals(listOf(CodingApprovalDecision.ALLOW_ONCE, CodingApprovalDecision.DENY), decisions)
        }
    }

    @Test fun rendersApprovalInNarrowChatAboveComposer() {
        val session = CodingSession("s", "p", "Сборка проекта", 0)
        ImageComposeScene(390, 760) {
            MagicPaperTheme {
                Surface {
                CodingChat(CodingProject("p", "MagicPaper", "/projects/MagicPaper", 0),
                    CodingSessionUi(session, messages = listOf(CodingMessage("u", CodingRole.USER, "Проверь сборку проекта", createdAt = 0)),
                        draft = CodingDraft(active = true, awaitingApproval = true), running = true),
                    busy = true, engineReady = true, onSend = { _, _ -> }, onAbort = {}, onPickAttachments = { _, _ -> },
                    approvals = listOf(request, request.copy(id = "b", sessionName = "Проверка изменений")))
                }
            }
        }.use { scene ->
            var frame = 0L
            repeat(20) { scene.render(++frame * 32_000_000L).close(); Thread.sleep(25) }
            val output = File("build/reports/approvals").apply { mkdirs() }
            File(output, "coding-approval.png").writeBytes(scene.render(++frame * 32_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
        }
    }
}
