package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperPinnedMessageTest {
    @Test fun pinHasItsOwnSpaceWithoutAddingAButtonRow() = runBlocking {
        for (fontScale in listOf(1f, 2f)) {
            for (label in listOf("commit all changes", "Длинное сообщение пользователя ".repeat(8))) {
                var pinned = Rect.Zero
                var plain = Rect.Zero
                var text = Rect.Zero
                ImageComposeScene(600, 3000) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        PaperTheme {
                            Column {
                                PaperPinnedMessage(1, {}, Modifier.widthIn(max = 260.dp)
                                    .onGloballyPositioned { pinned = it.boundsInRoot() }) {
                                    PaperText(label, Modifier.onGloballyPositioned { text = it.boundsInRoot() })
                                }
                                // Match the available text width to distinguish wrapping from button height.
                                PaperPinnedMessage(null, {}, Modifier.widthIn(max = 236.dp)
                                    .onGloballyPositioned { plain = it.boundsInRoot() }) {
                                    PaperText(label)
                                }
                            }
                        }
                    }
                }.use { scene ->
                    repeat(20) { scene.render((it + 1) * 16_000_000L).close() }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    val button = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                        .single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
                    assertTrue(text.right < button.boundsInRoot.left, "Text must stop before the pin hit area")
                    assertEquals(plain.height, pinned.height, "Pin must not add height")
                    assertTrue(pinned.width <= 260f)
                    assertTrue(button.boundsInRoot.right <= pinned.right)
                    assertTrue(button.boundsInRoot.bottom <= pinned.bottom)
                }
            }
        }
    }
}
