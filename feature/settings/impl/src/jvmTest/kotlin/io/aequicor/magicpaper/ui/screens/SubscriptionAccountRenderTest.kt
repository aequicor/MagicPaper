package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.OpenAiSubscriptionAccount
import io.aequicor.magicpaper.ui.OpenAiSubscriptionUi
import io.aequicor.magicpaper.ui.components.SubscriptionAccountAction
import java.io.File
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SubscriptionAccountRenderTest {
    @Test fun failedLimitsKeepSignedInStateAndVisibleRefreshAction() {
        for (scale in listOf(1f, 2f)) {
            val actions = mutableListOf<SubscriptionAccountAction>()
            ImageComposeScene(390, 440, density = Density(1f, scale)) {
                PaperTheme { PaperSurface { Column {
                    SubscriptionAccountContent(OpenAiSubscriptionUi(available = true,
                        account = OpenAiSubscriptionAccount(signedIn = true, rateLimitsUnavailable = true)), actions::add)
                } } }
            }.use { scene ->
                repeat(6) { scene.render(it * 16_000_000L).close() }
                val nodes = scene.nodes()
                val texts = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                assertTrue("✓ ChatGPT" in texts)
                assertTrue("Лимиты сейчас недоступны" in texts)
                assertFalse("Войти через ChatGPT" in texts)
                val refresh = nodes.first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Обновить" } == true }
                assertTrue(refresh.boundsInRoot.right <= 390 && refresh.boundsInRoot.bottom <= 440)
                val file = File("build/reports/subscription-account/limits-unavailable-390-$scale.png").apply { parentFile.mkdirs() }
                scene.render(100_000_000L).use { image -> file.writeBytes(image.encodeToData()!!.use { it.bytes }) }
            }
        }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
}
