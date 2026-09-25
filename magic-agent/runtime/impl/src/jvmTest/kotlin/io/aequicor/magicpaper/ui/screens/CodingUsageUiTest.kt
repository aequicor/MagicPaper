package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.components.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class CodingUsageUiTest {
    private var frame = 0L
    private fun tick(): Long { frame += 100_000_000; return frame }
    private fun SemanticsNode.hasText(value: String): Boolean = text() == value || children.any { it.hasText(value) }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun SemanticsNode.text() = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun ImageComposeScene.label(value: String) = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { name -> name.startsWith(value) } == true }
    private fun ImageComposeScene.click(node: SemanticsNode) {
        assertEquals(true, node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
        repeat(5) { render(tick()).close() }
    }
    private fun ImageComposeScene.snapshot(name: String) {
        val file = File("build/reports/usage/$name.png"); file.parentFile.mkdirs()
        render(tick()).use { image -> file.writeBytes(checkNotNull(image.encodeToData()).bytes) }
    }



    @Test fun contextAndSystemMessagesUseAgentLayoutAndKeepDraftOnResize() {
        for (width in listOf(390, 1000)) {
            val context = mutableStateOf<ContextUsageSnapshot?>(ContextUsageSnapshot("coding:s", "Fixture", 500, 1000, approximate = true))
            val compacting = mutableStateOf(false)
            val draft = CodingComposerDraft().also { it.text.value = "Сохранённый черновик" }
            ImageComposeScene(width, 800) {
                MagicPaperTheme { PaperPanel(Modifier.fillMaxSize()) {
                    Column {
                        Column(Modifier.weight(1f).padding(16.dp)) {
                            PaperSessionContextMessage("system", "Проверенные настройки сессии")
                            PaperSystemMessage { PaperText("Контекст автоматически сжат") }
                        }
                        CodingComposer(state = draft, enabled = true, busy = false, contextUsage = context.value,
                            contextCompacting = compacting.value,
                            controls = { PaperText("Fixture") }, onSend = { _, _ -> }, onAbort = {}, onPickAttachments = { _, _ -> })
                    }
                } }
            }.use { scene ->
                repeat(5) { scene.render(tick()).close() }
                val indicator = scene.label("Заполненность контекста")
                assertEquals("≈50%", indicator.config.getOrNull(SemanticsProperties.StateDescription))
                assertTrue(indicator.boundsInRoot.right <= width)
                val system = scene.nodes().first { it.text() == "Контекст автоматически сжат" }
                assertTrue(system.boundsInRoot.left < width / 2)
                scene.snapshot("chat-$width")
                assertFalse(scene.nodes().any { it.text() == "Проверенные настройки сессии" })
                scene.click(scene.nodes().first { it.hasText("Показать контекст") && it.config.getOrNull(SemanticsActions.OnClick) != null })
                assertTrue(scene.nodes().any { it.text() == "Проверенные настройки сессии" })
                scene.click(scene.nodes().first { it.hasText("Свернуть") && it.config.getOrNull(SemanticsActions.OnClick) != null })
                scene.click(indicator)
                assertTrue(scene.nodes().any { it.text().contains("Приблизительная оценка") })
                assertEquals("Сохранённый черновик", draft.text.value)
                context.value = null
                repeat(5) { scene.render(tick()).close() }
                assertEquals("—", scene.label("Заполненность контекста").config.getOrNull(SemanticsProperties.StateDescription))
                context.value = ContextUsageSnapshot("coding:s", "Fixture", 500, 1000, approximate = true)
                compacting.value = true
                repeat(5) { scene.render(tick()).close() }
                assertEquals("Сжатие контекста", scene.label("Заполненность контекста").config.getOrNull(SemanticsProperties.StateDescription))
                scene.snapshot("chat-compacting-$width")
            }
        }
    }

    @Test fun contextDetailsShowThePlanWindowsOfTheSubscription() {
        val now = 1_790_252_000_000L
        val plan = PlanUsage(ProviderType.ANTHROPIC_SUBSCRIPTION, listOf(
            PlanUsageWindow("seven_day_opus", .004f, 10_080, now / 1000 + 5 * 86_400, "Opus"),
            PlanUsageWindow("seven_day", .01f, 10_080, now / 1000 + 5 * 86_400),
            PlanUsageWindow("five_hour", .11f, 300, now / 1000 + 4 * 3600 + 45 * 60)), plan = "max")
        // The details' own popup width.
        for ((width, fontScale) in listOf(340 to 1f, 340 to 1.6f)) {
            val opened = mutableListOf<Unit>()
            ImageComposeScene(width, 520, density = Density(1f, fontScale)) {
                MagicPaperTheme { PaperPanel(Modifier.fillMaxSize()) {
                    Column {
                        ContextUsageDetails(ContextUsageSnapshot("coding:s", "claude-opus", 584_800, 1_000_000), "claude-opus",
                            compacting = false, plan = plan, now = now)
                        ContextUsageIndicator(ContextUsageSnapshot("coding:s", "claude-opus", 584_800, 1_000_000),
                            plan = plan, onOpen = { opened += Unit })
                    }
                } }
            }.use { scene ->
                repeat(3) { scene.render(tick()).close() }
                val texts = scene.nodes().map { it.text() }
                assertTrue("584,8 тыс. / 1 млн" in texts && "58%" in texts, texts.toString())
                // Every share stands on the trailing guide, whether or not its reset moved under the title.
                val button = scene.label("Заполненность контекста").boundsInRoot.top
                val shares = scene.nodes().filter { it.text() in setOf("58%", "11%", "1%", "0%") && it.boundsInRoot.bottom <= button }
                    .map { it.boundsInRoot.right }
                assertEquals(4, shares.size)
                assertEquals(1, shares.distinct().size, shares.toString())
                assertTrue("Лимиты Claude · Max" in texts)
                // Shortest window first; the plan-wide week says so beside the Opus one.
                val titles = listOf("5 часов", "Неделя · все модели", "Неделя · Opus")
                assertEquals(titles, texts.filter { it in titles })
                assertTrue("Сброс через 4 ч 45 мин" in texts)
                assertTrue(listOf("11%", "1%", "0%").all { it in texts })
                scene.nodes().filter { it.text() in titles }.forEach { assertTrue(it.boundsInRoot.right <= width) }
                scene.snapshot("plan-details-$width-x$fontScale")
                scene.click(scene.label("Заполненность контекста"))
                assertEquals(1, opened.size)
            }
        }
    }

    @Test fun planWithoutFiguresShowsLoadingState() {
        ImageComposeScene(360, 300) {
            MagicPaperTheme { PaperPanel(Modifier.fillMaxSize()) {
                ContextUsageDetails(null, "", compacting = false, plan = PlanUsage(ProviderType.ANTHROPIC_SUBSCRIPTION), now = 0)
            } }
        }.use { scene ->
            repeat(3) { scene.render(tick()).close() }
            val texts = scene.nodes().map { it.text() }
            assertTrue("Нет данных" in texts)
            assertTrue("Лимиты Claude" in texts)
            assertTrue("Получаем данные о лимитах…" in texts)
            scene.snapshot("plan-loading-360")
        }
    }
}
