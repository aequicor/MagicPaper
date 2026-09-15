package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PaperExpandableImageTest {
    @Test
    fun clickChangesThumbnailToExpandedAccessibleState() {
        val expanded = mutableStateOf(false)
        val scene = ImageComposeScene(520, 380) {
            PaperTheme {
                PaperExpandableImage(ImageBitmap(160, 90), "Схема.png", expanded.value, { expanded.value = !expanded.value })
            }
        }
        try {
            scene.render(0).close()
            val collapsed = scene.semanticsOwners.single().unmergedRootSemanticsNode.children.single()
            assertEquals("Миниатюра изображения", collapsed.config.getOrNull(SemanticsProperties.StateDescription))
            assertTrue(collapsed.config[SemanticsActions.OnClick].action?.invoke() == true)
            scene.render(110_000_000).close()
            scene.render(220_000_000).close()
            scene.render(400_000_000).close()
            val revealed = scene.semanticsOwners.single().unmergedRootSemanticsNode.children.single()

            assertEquals("Изображение раскрыто", revealed.config.getOrNull(SemanticsProperties.StateDescription))
        } finally {
            scene.close()
        }
    }
}
