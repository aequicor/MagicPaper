package io.aequicor.magicpaper.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
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

@OptIn(ExperimentalCoroutinesApi::class)
class CodingComposerRenderTest {
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
