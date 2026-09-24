package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingRecovery
import io.aequicor.magicpaper.domain.CodingRunRecorder
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import java.io.File
import kotlin.test.*

/** A signed-out engine is fixed from the transcript: the error carries the sign-in, not a command to type. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class CodingRecoveryRowRenderTest {
    private val recovery = CodingRecovery.SignIn(CodingEngine.CLAUDE_CODE)
    private val step = CodingRunRecorder().run {
        apply(CodingEvent.Failed("Claude Code не авторизован. Войдите в аккаунт Claude или укажите ключ API в подключении Anthropic.", recovery))
        timeline().single { it.kind == CodingStepKind.ERROR }
    }

    @Test fun errorOffersItsRecoveryAndShowsItWhilePending() {
        val pending = mutableStateOf(emptySet<CodingRecovery>())
        val started = mutableListOf<CodingRecovery>()
        val cancelled = mutableListOf<CodingRecovery>()
        ImageComposeScene(420, 200) {
            MagicPaperTheme {
                CompositionLocalProvider(LocalCodingRecovery provides CodingRecoveryHandlers(pending.value, { started += it }, { cancelled += it })) {
                    Column(Modifier.fillMaxWidth()) { CodingStepRow(step, live = false) }
                }
            }
        }.use { scene ->
            var frame = 0L
            fun render() = repeat(6) { scene.render(++frame * 16_000_000L).close() }
            fun snapshot(name: String) = File(File("build/reports/coding-recovery").apply { mkdirs() }, name)
                .writeBytes(scene.render(++frame * 16_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
            render()
            snapshot("sign-in.png")
            scene.action("Войти в Claude Code").config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(listOf<CodingRecovery>(recovery), started)

            pending.value = setOf(recovery)
            render()
            snapshot("sign-in-pending.png")
            assertTrue(scene.texts().any { it == "Подтвердите вход в браузере" })
            assertTrue(scene.actions().none { "Войти в Claude Code" in it.description() }, "A pending sign-in is not offered twice")
            scene.action("Отменить").config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(listOf<CodingRecovery>(recovery), cancelled)
        }
    }

    @Test fun surfaceWithoutHandlersShowsTheErrorAlone() {
        ImageComposeScene(420, 160) {
            MagicPaperTheme { Column(Modifier.fillMaxWidth()) { CodingStepRow(step, live = false) } }
        }.use { scene ->
            repeat(4) { scene.render(it * 16_000_000L).close() }
            assertTrue(scene.actions().none { "Войти" in it.description() })
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun ImageComposeScene.actions() = nodes().filter { it.config.contains(SemanticsActions.OnClick) }
    private fun ImageComposeScene.action(label: String) = actions().single { label in it.description() }
    private fun ImageComposeScene.texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun SemanticsNode.description(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsActions.OnClick)?.label)).joinToString()
}
