package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.components.ComputerUsePanel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class CodingWorkspaceRenderTest {
    @Test fun workspaceKeepsWritingAreaAndActionsVisibleAtDesktopWidths() {
        val project = CodingProject("p", "MagicPaper", "/projects/MagicPaper", 0)
        val session = CodingSessionUi(CodingSession("s", "p", "Дизайн рабочего пространства", 0), messages = listOf(
            CodingMessage("request", CodingRole.USER, "Обнови интерфейс проектов. Сохрани фон окна и разделители.", createdAt = 0),
            CodingMessage("answer", CodingRole.AGENT, "", createdAt = 1, steps = listOf(
                CodingStep(CodingStepKind.ANSWER, "Проверю навигацию, сообщения и поле ввода. Начну с компонентов дизайн-системы."),
                CodingStep(CodingStepKind.TOOL, "Чтение · PaperComponents.kt", result = "public fun PaperButton(…)"),
                CodingStep(CodingStepKind.EXEC, "Команда · ./gradlew :designSystem:jvmTest", result = "BUILD SUCCESSFUL"),
                CodingStep(CodingStepKind.ANSWER, "Готово. Поле ввода отделено от действий, а команды раскрываются в компактных строках.\n\nШрифт сообщений пользователя и агента одинаковый."),
            )),
        ))
        val ui = CodingUi(projects = listOf(project, CodingProject("b", "Заметки", "/projects/Notes", 0)),
            current = project, currentSessionId = "s", sessions = listOf(session,
                CodingSessionUi(CodingSession("older", "p", "Проверка сборки", 1))))
        for (width in listOf(1240, 720, 600)) ImageComposeScene(width, 860) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas)) {
                    ResizableProjectPanels(sidebar = { modifier ->
                        ProjectsPanel(ui, {}, {}, {}, {}, {}, {}, {}, modifier)
                    }) {
                        Column {
                            ComputerUsePanel(ComputerUseState(), "s", false, {}, {}, {}, {})
                            CodingChat(project, session, false, true, { _, _ -> }, {}, { _, _ -> },
                                listState = LazyListState(), onInteractionMode = {},
                                modelChip = { io.aequicor.magicpaper.ui.components.CodingModelChip(
                                    LlmProfile("fixture", "GPT-6 · Astra"), false, {}) })
                        }
                    }
                }
            }
        }.use { scene ->
            repeat(30) { scene.render(it * 32_000_000L).close(); Thread.sleep(10) }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
            for (label in listOf("Отправить", "Что нужно сделать?", "GPT-6 · Astra")) {
                val node = nodes.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }
                assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width, "$label clipped at $width")
                assertTrue(node.boundsInRoot.bottom <= 860, "$label below viewport")
                if (label == "Отправить") assertTrue(node.boundsInRoot.right > width - 64, "Send must stay at the trailing edge")
            }
            val sendLabel = nodes.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Отправить" } }
            val sendControl = nodes.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Отправить") == true }
            assertTrue(kotlin.math.abs(sendLabel.boundsInRoot.center.x - sendControl.boundsInRoot.center.x) < 1f, "Send label horizontally centered")
            assertTrue(kotlin.math.abs(sendLabel.boundsInRoot.center.y - sendControl.boundsInRoot.center.y) < 1f, "Send label vertically centered")
            val transcript = nodes.first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null && it.boundsInRoot.left >= 280f }
            val input = nodes.first { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.SetText) != null }
            assertTrue(transcript.boundsInRoot.bottom > input.boundsInRoot.bottom, "Transcript must continue behind the floating composer at $width")
            val directory = File("build/reports/coding-workspace").apply { mkdirs() }
            File(directory, "workspace-$width.png").writeBytes(scene.render(1_500_000_000L).use { image ->
                image.encodeToData()!!.use { it.bytes }
            })
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, input.boundsInRoot.center)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, input.boundsInRoot.center)
            repeat(15) { scene.render(1_600_000_000L + it * 32_000_000L).close(); Thread.sleep(5) }
            File(directory, "workspace-focused-$width.png").writeBytes(scene.render(2_200_000_000L).use { image ->
                image.encodeToData()!!.use { it.bytes }
            })

        }
    }
    @Test fun longTranscriptContinuesBehindRaisedComposer() {
        val project = CodingProject("p", "Project", "/project", 0)
        val session = CodingSessionUi(CodingSession("s", "p", "Chat", 0), messages = listOf(
            CodingMessage("lead", CodingRole.USER, (1..6).joinToString("\n") { "Предыдущее сообщение $it" }, createdAt = 0),
            CodingMessage("long", CodingRole.USER, (1..100).joinToString("\n") { "Строка сообщения $it" }, createdAt = 1),
        ))
        val list = LazyListState()
        ImageComposeScene(640, 600) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas)) {
                    CodingChat(project, session, false, true, { _, _ -> }, {}, { _, _ -> }, listState = list,
                        pins = listOf(RequestPinGroup(RequestPin("lead", "Закреплённый запрос", "Пользователь"))))
                }
            }
        }.use { scene ->
            repeat(30) { scene.render(it * 32_000_000L).close(); Thread.sleep(10) }
            kotlin.test.assertTrue(list.dispatchRawDelta(-64f) < 0f, "Exercise scrolling above the newest message")
            repeat(10) { scene.render(1_100_000_000L + it * 32_000_000L).close() }
            assertTrue(list.canScrollBackward, "Top edge shadow must be exercised while scrolled")
            val bytes = scene.render(1_500_000_000L).use { it.encodeToData()!!.use { it.bytes } }
            val directory = File("build/reports/coding-workspace").apply { mkdirs() }
            File(directory, "composer-fade.png").writeBytes(bytes)
            list.dispatchRawDelta(400f)
            repeat(15) { scene.render(1_600_000_000L + it * 32_000_000L).close() }
            val pinnedBytes = scene.render(2_200_000_000L).use { it.encodeToData()!!.use { it.bytes } }
            File(directory, "top-shadow-pinned.png").writeBytes(pinnedBytes)
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            val pin = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.first {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Закреплённый запрос" }
            }
            assertTrue(pin.boundsInRoot.top < 32f, "Pin stays at the top")
            val pixels = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(pinnedBytes))
            fun red(y: Int) = (pixels.getRGB(0, y) shr 16) and 255
            assertTrue(red(0) < red(100) - 20, "Shadow starts at the top and touches the left boundary")

        }
    }

}
