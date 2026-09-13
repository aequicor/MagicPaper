package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.CodingModelChip
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class CodingComposerRenderTest {
    @Test fun imageAttachmentsCanBeRemovedIndividuallyAndSentBeforePreviewCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val draft = io.aequicor.magicpaper.ui.components.CodingComposerDraft().apply {
                // Invalid raster bytes intentionally leave both previews in ERROR/LOADING;
                // attachment delivery must stay independent of preview success.
                attachments.value = listOf(
                    Attachment("same", "first.png", "image/png", 3, "YWJj", AttachmentKind.IMAGE),
                    Attachment("same", "second.png", "image/png", 3, "ZGVm", AttachmentKind.IMAGE),
                )
            }
            val sent = mutableListOf<List<Attachment>>()
            ImageComposeScene(680, 220) { MagicPaperTheme { Surface {
                CodingComposer(state = draft, enabled = true, busy = false,
                    onSend = { _, attachments -> sent += attachments }, onAbort = {}, onPickAttachments = { _, _ -> })
            } } }.use { scene ->
                repeat(4) { scene.render(it * 16_000_000L).close(); runCurrent() }
                fun nodes(): List<SemanticsNode> {
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    return scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                }
                val remove = nodes().single {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Удалить first.png · 3 Б")
                }
                assertTrue(remove.config[SemanticsActions.OnClick].action?.invoke() == true)
                scene.render(80_000_000L).close(); runCurrent()
                assertEquals(listOf("second.png"), draft.attachments.value.map { it.name })
                val send = nodes().single {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Отправить")
                }
                assertTrue(send.config[SemanticsActions.OnClick].action?.invoke() == true)
                assertEquals(listOf(listOf("second.png")), sent.map { it.map(Attachment::name) })
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun planningQuestionRetainsDraftUntilOwnerAcknowledgesAndEmptyComposerCanContinue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (width in listOf(390, 1000)) {
                val draft = io.aequicor.magicpaper.ui.components.CodingComposerDraft().apply {
                    text.value = "Почему результат не принят?"
                }
                val sent = mutableListOf<String>()
                var submittedVersion = -1L
                var resumed = 0
                ImageComposeScene(width, 180) {
                    MagicPaperTheme { Surface {
                        CodingComposer(state = draft, planning = true, enabled = true, busy = false,
                            onSend = { text, _ -> submittedVersion = draft.version; sent += text }, onResume = { _, _ -> resumed++ },
                            onAbort = {}, onPickAttachments = { _, _ -> })
                    } }
                }.use { scene ->
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    var frame = 0L
                    fun draw() { runCurrent(); scene.render(++frame * 16_000_000L).close(); runCurrent() }
                    fun click(label: String) {
                        // Snapshot invalidation may arrive after the first frame under the full-suite load.
                        repeat(60) {
                            draw()
                            val node = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.firstOrNull {
                                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
                            }
                            if (node != null) {
                                scene.sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
                                scene.sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
                                return
                            }
                        }
                        fail("Composer action '$label' did not become visible at width $width")
                    }
                    repeat(6) { draw() }
                    click("Отправить")
                    repeat(6) { draw() }
                    assertEquals(listOf("Почему результат не принят?"), sent)
                    assertEquals(0, resumed)
                    assertEquals("Почему результат не принят?", draft.text.value,
                        "Rendering alone cannot clear a request before its owner persists it")
                    assertTrue(draft.clearIfUnchanged(submittedVersion))
                    assertEquals("", draft.text.value)
                    draw()
                    click("Продолжить")
                    repeat(6) { draw() }
                    assertEquals(1, resumed)
                    assertEquals(1, sent.size)
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun continueWorksWithEmptyComposerAtBothWidthsAndSendStaysDisabled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (width in listOf(390, 1000)) for (resumable in listOf(false, true)) {
                var bounds = Rect.Zero
                var resumed = 0
                var sent = 0
                ImageComposeScene(width, 120) {
                    MagicPaperTheme { Surface {
                        Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
                            CodingComposer(enabled = true, busy = false, onSend = { _, _ -> sent++ },
                                onAbort = {}, onPickAttachments = { _, _ -> },
                                onResume = if (resumable) { { text, attachments ->
                                    assertEquals("", text); assertTrue(attachments.isEmpty()); resumed++
                                } } else null)
                        }
                    } }
                }.use { scene ->
                    repeat(6) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val label = if (resumable) "Продолжить" else "Отправить"
                    val button = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.first {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
                    }.boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, button)
                    scene.sendPointerEvent(PointerEventType.Release, button)
                    scene.render(112_000_000L).close(); runCurrent()
                    assertEquals(if (resumable) 1 else 0, resumed, "Continue should respond at width $width")
                    assertEquals(0, sent)
                    val output = File("build/reports/coding-composer").apply { mkdirs() }
                    File(output, "resume-$width-$resumable.png").writeBytes(scene.render(128_000_000L).use {
                        it.encodeToData()!!.use { data -> data.bytes }
                    })
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun reasoningStatusRendersWithAndWithoutSummaryDuringCommand() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val command = CodingStep(CodingStepKind.EXEC, "⚒ command · bash -lc ./gradlew", running = true)
            val drafts = listOf(
                CodingDraft(active = true),
                CodingDraft(steps = listOf(command), active = true),
                CodingDraft(steps = listOf(CodingStep(CodingStepKind.THINKING, "**Проверю сборку проекта**\n\n- Открою `build.gradle.kts`\n- Проверю *зависимости*"), command), active = true),
            )
            val output = File("build/reports/coding-status").apply { mkdirs() }
            drafts.forEachIndexed { index, draft ->
              for (expanded in listOf(false, true)) {
                ImageComposeScene(680, 180) {
                    MagicPaperTheme { Surface { AgentMessageStatus(draft, expanded = expanded, onToggle = {}) } }
                }.use { scene ->
                    // Markdown parses on a background dispatcher; allow it to reach the scene.
                    repeat(20) {
                        scene.render(it * 32_000_000L).close()
                        Thread.sleep(25)
                        runCurrent()
                    }
                    File(output, "$index-$expanded.png").writeBytes(scene.render(640_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                }
              }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun modelRemainsClickableBesideSendAtBothWidths() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (width in listOf(390, 1000)) {
                var bounds = Rect.Zero
                var opened = false
                var expectedWidth = 0f
                ImageComposeScene(width, 620) {
                    MagicPaperTheme { Surface {
                        CodingChat(CodingProject("p", "Проект", "/project", 1),
                            CodingSessionUi(CodingSession("s", "p", "Этап", 1, stageId = "stage"),
                                messages = (1..12).map { CodingMessage("$it", CodingRole.AGENT, "Сообщение $it: содержимое ленты под полем ввода.", createdAt = 1) }),
                            false, true, { _, _ -> }, {}, { _, _ -> }, modelChip = {
                                val profile = LlmProfile("model", "GPT-5.6-Terra", modelId = "gpt-5.6-terra")
                                val measurer = rememberTextMeasurer()
                                val nameWidth = measurer.measure(profile.modelName(profile.selectionKey), MaterialTheme.typography.labelMedium).size.width
                                val effortWidth = measurer.measure(profile.effortLabel(ModelDefaults.capability(profile)), MaterialTheme.typography.labelSmall).size.width
                                expectedWidth = maxOf(nameWidth, effortWidth) + with(LocalDensity.current) { 16.dp.toPx() }
                                CodingModelChip(profile, false,
                                    { opened = true }, Modifier.onGloballyPositioned { bounds = it.boundsInRoot() })
                            })
                    } }
                }.use { scene ->
                    repeat(6) { scene.render(it * 16_000_000L).close(); runCurrent() }
                    assertTrue(bounds.width >= expectedWidth, "Model labels must fit without truncation (need $expectedWidth): $bounds")
                    assertTrue(bounds.right > width - 180, "Model should sit next to send: $bounds")
                    scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                    scene.sendPointerEvent(PointerEventType.Release, bounds.center)
                    scene.render(112_000_000L).close(); runCurrent()
                    assertTrue(opened, "Model button must respond at width $width")
                    val output = File("build/reports/coding-composer").apply { mkdirs() }
                    File(output, "$width.png").writeBytes(scene.render(128_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}
