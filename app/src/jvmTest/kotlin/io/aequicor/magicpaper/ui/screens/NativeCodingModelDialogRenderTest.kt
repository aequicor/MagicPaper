package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.NativeCodingModelChip
import io.aequicor.magicpaper.ui.components.NativeCodingModelDialog
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

/** Named states of the engine-catalog model picker; each is rendered to build/reports/native-model for acceptance. */
@OptIn(ExperimentalComposeUiApi::class)
class NativeCodingModelDialogRenderTest {
    private val astra = CodingModel("openai", "gpt-6-astra", "GPT-6 Astra", contextWindow = 258_400,
        levels = listOf("low", "medium", "high", "xhigh", "max", "ultra"), defaultLevel = "medium", acceptsImages = true)
    private val textOnly = CodingModel("openai", "gpt-text", "Text only")
    private val snapshot = CodingModelSnapshot(CodingEngine.CODEX, listOf(astra, textOnly), 1)
    private fun pick(id: String = astra.id, level: String? = null) = CodingModelSelection(CodingEngine.CODEX, "openai", id, level)

    private class Frame(val texts: List<String>)

    private fun render(name: String, width: Int = 640, height: Int = 700, content: @androidx.compose.runtime.Composable () -> Unit): Frame {
        ImageComposeScene(width, height) { MagicPaperTheme { Surface { content() } } }.use { scene ->
            var frame = 0L
            repeat(5) { scene.render(++frame * 16_000_000L).close() }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            val texts = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            File("build/reports/native-model").mkdirs()
            File("build/reports/native-model/$name.png").writeBytes(
                scene.render(++frame * 16_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
            return Frame(texts)
        }
    }
    private fun dialog(selection: CodingModelSelection?, snapshot: CodingModelSnapshot? = this.snapshot, refreshing: Boolean = false): @androidx.compose.runtime.Composable () -> Unit = {
        NativeCodingModelDialog(CodingEngine.CODEX, snapshot, selection, refreshing, onSelect = {}, onRefresh = {}, onDismiss = {})
    }

    @Test fun defaultLevelNamesTheEnginesRealDefaultAndOffersOnlyDeclaredLevels() {
        val frame = render("default-level", content = dialog(pick()))
        assertContains(frame.texts, "● GPT-6 Astra")
        assertContains(frame.texts, "Text only")
        assertContains(frame.texts, "Уровень: по умолчанию: medium")
        assertContains(frame.texts, "по умолчанию: medium")
        listOf("low", "medium", "high", "xhigh", "max", "ultra").forEach { assertContains(frame.texts, it) }
    }

    @Test fun explicitUltraIsShownAsUltraNotFoldedIntoMax() {
        val frame = render("explicit-ultra", content = dialog(pick(level = "ultra")))
        assertContains(frame.texts, "Уровень: ultra")
    }

    @Test fun modelWithoutThinkingHasNoLevelControl() {
        val frame = render("no-thinking", content = dialog(pick(textOnly.id)))
        assertTrue(frame.texts.none { it.startsWith("Уровень:") })
    }

    @Test fun savedModelThatLeftTheCatalogIsExplainedNotSilentlyReplaced() {
        val frame = render("missing-model", content = dialog(pick("gpt-gone")))
        assertTrue(frame.texts.any { it.contains("«gpt-gone» нет в каталоге движка") })
    }

    @Test fun levelTheModelNoLongerDeclaresIsExplainedAndTheDeclaredOnesRemainSelectable() {
        val frame = render("unsupported-level", content = dialog(pick(level = "minimal")))
        assertTrue(frame.texts.any { it.contains("не поддерживает уровень «minimal»") })
        assertContains(frame.texts, "ultra")
    }

    @Test fun emptyCatalogInvitesARefreshAndBusyStateIsAnnounced() {
        assertContains(render("empty", content = dialog(null, snapshot = null)).texts, "Список моделей движка пока не загружен.")
        val busy = render("refreshing", content = dialog(null, snapshot = null, refreshing = true))
        assertContains(busy.texts, "Загружаем список моделей…")
        assertContains(busy.texts, "Обновляем список…")
    }

    @Test fun composerChipShowsTheCatalogNameAndTheRealDefaultLevel() {
        val frame = render("chip", width = 320, height = 120) {
            Column { NativeCodingModelChip(pick(), snapshot, onClick = {}) }
        }
        assertContains(frame.texts, "GPT-6 Astra")
        assertContains(frame.texts, "по умолчанию: medium")
        val unknown = render("chip-unknown", width = 320, height = 120) {
            Column { NativeCodingModelChip(pick("gpt-gone", "high"), snapshot, onClick = {}) }
        }
        assertContains(unknown.texts, "gpt-gone")
        assertContains(unknown.texts, "high")
    }
}
