package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.domain.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SessionBrowserRenderTest {
    private fun platformSaveable(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Number, is Char -> true
        is MutableState<*> -> platformSaveable(value.value)
        is List<*> -> value.all(::platformSaveable)
        is Array<*> -> value.all(::platformSaveable)
        is Map<*, *> -> value.all { (key, item) -> platformSaveable(key) && platformSaveable(item) }
        else -> false
    }

    @Test fun archiveSearchSelectionAndRestoreUseVisibleControlsAtNarrowAndLargeTextSizes() {
        for ((width, fontScale) in listOf(320 to 1f, 240 to 1.5f)) {
            var query by mutableStateOf("")
            var archives by mutableStateOf(true)
            var chat by mutableStateOf(ChatSession("archived", "Восстановление дочерних сессий после перезапуска", 1, 1,
                messages = listOf(ChatMessage("m", ChatRole.USER, "Поиск по переписке: уникальное слово", 1)), archived = true))
            var selected: String? = null
            val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = ::platformSaveable)
            ImageComposeScene(width, 820) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalSaveableStateRegistry provides registry) {
                    PaperTheme {
                        PaperSurface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            SessionBrowserControls(query, { query = it }, archives, if (chat.archived) 1 else 0, { archives = !archives })
                            SessionBrowserResults(searchSessions(listOf(chat), emptyList(), emptyList(), query, archives),
                                archives, query.isNotBlank(), selected, false,
                                { selected = it.id }, { chat = chat.copy(archived = false) }, Modifier.weight(1f))
                        }
                        }
                    }
                }
            }.use { scene ->
                var frame = 0L
                fun draw() { repeat(6) { scene.render(++frame * 16_000_000L).close() } }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                fun hasText(text: String) = all().any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
                fun click(text: String) {
                    val node = all().first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null }
                    assertTrue(node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
                    node.config[SemanticsActions.OnClick].action!!.invoke()
                    draw()
                }
                fun capture(name: String) {
                    val dir = File("build/reports/session-browser").apply { mkdirs() }
                    scene.render(++frame * 16_000_000L).use { image ->
                        File(dir, "$name-$width.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                    }
                }
                draw()
                assertTrue(hasText("Чат · В архиве"))
                capture("archive")
                click(chat.title)
                assertEquals("archived", selected)
                assertTrue(chat.archived)
                click("← Все сессии")
                all().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                    .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("уникальное"))
                draw()
                assertTrue(hasText("Чат · В архиве"))
                capture("search")
                click("Разархивировать")
                assertFalse(chat.archived)
                assertFalse(hasText("Разархивировать"))
                click("Очистить поиск")
                click("Архив (0)")
                assertTrue(hasText("Архив пуст"))
                registry.performSave()
                capture("empty")
            }
        }
    }
}
