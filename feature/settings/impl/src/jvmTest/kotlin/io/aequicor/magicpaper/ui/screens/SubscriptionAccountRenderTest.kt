package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.OpenAiSubscriptionAccount
import io.aequicor.magicpaper.ui.ClaudeSubscriptionUi
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
    @Test fun claudeSubscriptionOffersTheCliSignInAndShowsItWhilePending() {
        val states = listOf(
            "signed-out" to ClaudeSubscriptionUi(available = true, signedIn = false),
            "pending" to ClaudeSubscriptionUi(available = true, signedIn = false, signingIn = true),
            "signed-in" to ClaudeSubscriptionUi(available = true, signedIn = true),
            "checking" to ClaudeSubscriptionUi(available = true, signedIn = true, checking = true),
            "signing-out" to ClaudeSubscriptionUi(available = true, signedIn = true, signingOut = true),
            "unavailable" to ClaudeSubscriptionUi(available = false),
        )
        for ((name, auth) in states) {
            var signIns = 0; var cancels = 0; var signOuts = 0
            ImageComposeScene(390, 260) {
                PaperTheme { PaperSurface { Column { ClaudeSubscriptionAccount(auth, { signIns++ }, { cancels++ }, {}, { signOuts++ }) } } }
            }.use { scene ->
                repeat(6) { scene.render(it * 16_000_000L).close() }
                val nodes = scene.nodes()
                val texts = nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                fun action(label: String) = nodes.firstOrNull { node ->
                    node.config.contains(SemanticsActions.OnClick) && node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label }
                }
                /** A text action: the clickable that holds the label. */
                fun labelled(label: String) = nodes.firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }
                    ?.let { text -> generateSequence(text) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) } }
                when (name) {
                    "signed-out" -> {
                        action("Войти в Claude Code")!!.config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(1, signIns)
                        assertNull(labelled("Выйти"), "There is nothing to leave")
                    }
                    "pending" -> {
                        assertTrue("Подтвердите вход в браузере" in texts)
                        action("Отменить")!!.config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(1, cancels)
                    }
                    "signed-in" -> {
                        assertTrue("✓ Claude Code: вход выполнен" in texts); assertNull(action("Войти в Claude Code"))
                        labelled("Выйти")!!.config[SemanticsActions.OnClick].action!!.invoke(); assertEquals(1, signOuts)
                    }
                    "checking" -> {
                        assertTrue(labelled("Обновить")!!.config.contains(SemanticsProperties.Disabled))
                        assertFalse(labelled("Выйти")!!.config.contains(SemanticsProperties.Disabled),
                            "A dead token passes the check, so leaving the account must not wait for it")
                    }
                    "signing-out" -> {
                        assertTrue("Выхожу из Claude Code…" in texts)
                        assertNull(labelled("Выйти"), "A second sign-out cannot start")
                        assertNull(labelled("Обновить"), "A check would answer for the old login")
                    }
                    "unavailable" -> assertTrue(texts.any { "только в desktop" in it })
                }
                val file = File("build/reports/subscription-account/claude-$name.png").apply { parentFile.mkdirs() }
                scene.render(100_000_000L).use { image -> file.writeBytes(image.encodeToData()!!.use { it.bytes }) }
            }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
}
