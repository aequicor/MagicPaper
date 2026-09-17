package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ResearchSourcesPaneRenderTest {
    @Test fun sourceTabsExposeCountsEmptyStateAndRecoveryAtEverySize() {
        data class Case(val name: String, val width: Int, val height: Int, val scale: Float = 1f, val question: Boolean = false, val saving: Boolean = false)
        for (case in listOf(Case("sources", 340, 500), Case("narrow", 240, 580), Case("large-text", 400, 850, 2f),
            Case("question-empty", 340, 280, question = true), Case("saving", 340, 500, saving = true))) {
            val scene = onUi { ImageComposeScene(case.width, case.height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, case.scale)) {
                    ResearchSourcesPanePreview(case.question, case.saving)
                }
            } }
            try {
                onUi {
                    repeat(12) { scene.render(it * 32_000_000L).close() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val nodes = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    fun text(value: String) = nodes.first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == value } }
                    val tabs = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                    assertEquals(2, tabs.size)
                    assertEquals(tabs[0].boundsInRoot.bottom, tabs[1].boundsInRoot.bottom, .5f,
                        "Tab indicators share one baseline even when a label wraps")
                    assertEquals(listOf(!case.question, case.question), tabs.map { it.config[SemanticsProperties.Selected] })
                    assertTrue(text("6").boundsInRoot.width > 0)
                    assertTrue(text("0").boundsInRoot.width > 0)
                    if (case.question) {
                        assertTrue(text("Добавить источник").boundsInRoot.width > 0)
                        assertFalse(nodes.any { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox })
                    } else {
                        assertTrue(text("Выбрано 5 из 6").boundsInRoot.width > 0)
                        val error = text("HTTP 403").boundsInRoot
                        val domain = text("example.org").boundsInRoot
                        assertEquals(domain.center.y, error.center.y, 1f)
                        assertTrue(domain.right <= error.left)
                        val remove = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                            .contains("Убрать источник: Практика интеграции") }
                        assertEquals(case.saving, remove.config.contains(SemanticsProperties.Disabled))
                    }
                    for (node in nodes.filter { it.config.contains(SemanticsActions.OnClick) }) {
                        assertTrue(node.boundsInRoot.right <= case.width, "Action clipped: ${case.name} ${node.config}")
                    }
                    val file = File("build/reports/source-tabs/${case.name}.png").apply { parentFile.mkdirs() }
                    scene.render(400_000_000L).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
                }
            } finally { onUi { scene.close() } }
        }
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
