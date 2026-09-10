package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.domain.OrganismLimits
import io.aequicor.magicpaper.ui.ModelSettingsFixture
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class AgentLimitsSettingsRenderTest {
    @Test fun saveRejectsInvalidLimitsAndPersistsOnlyAnExplicitValidDraft() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fixture = ModelSettingsFixture()
            val vm = fixture.prepare()
            val initial = fixture.settings.load()
            ImageComposeScene(1000, 1100) {
                val state by vm.state.collectAsState()
                MagicPaperTheme { SettingsScreen(vm, state) }
            }.use { scene ->
                scene.draw()
                scene.setField(AgentLimitField.TOKENS, "-2")
                scene.draw()
                assertTrue(scene.action("Сохранить настройки").config.contains(SemanticsProperties.Disabled))
                assertEquals(initial, fixture.settings.load())
                scene.setField(AgentLimitField.TOKENS, "750000")
                scene.draw()
                assertFalse(scene.action("Сохранить настройки").config.contains(SemanticsProperties.Disabled))
                scene.action("Сохранить настройки").config[SemanticsActions.OnClick].action!!.invoke()
                advanceUntilIdle()
                scene.draw()
                assertEquals(initial.copy(agentLimits = OrganismLimits(tokens = 750_000)), fixture.settings.load())
                scene.action("Снять ограничения").config[SemanticsActions.OnClick].action!!.invoke()
                scene.draw()
                assertEquals(750_000L, fixture.settings.load().agentLimits.tokens, "Reset remains a draft until Save")
                scene.action("Сохранить настройки").config[SemanticsActions.OnClick].action!!.invoke()
                advanceUntilIdle()
                assertEquals(initial, fixture.settings.load())
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun optionalLimitsStayReachableAtNarrowWidthAndLargeText() {
        val output = File("build/reports/agent-limits").apply { mkdirs() }
        for ((width, scale) in listOf(390 to 1f, 1000 to 1f, 390 to 2f)) {
            var draft = AgentLimitsDraft()
            ImageComposeScene(width, 2100, density = Density(1f, scale)) {
                var fields by remember { mutableStateOf(draft) }
                MagicPaperTheme { PaperPanel {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AgentLimitsSettingsSection(fields) { fields = it; draft = it }
                    }
                } }
            }.use { scene ->
                scene.draw()
                for (field in AgentLimitField.entries) {
                    val node = scene.field(field)
                    assertTrue(node.boundsInRoot.left >= 0f)
                    assertTrue(node.boundsInRoot.right <= width.toFloat())
                    assertTrue(walk(node).any { it.config.getOrNull(SemanticsActions.SetText) != null })
                }
                scene.render(90_000_000L).use { image ->
                    File(output, "unlimited-$width-$scale.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                }
                scene.setField(AgentLimitField.TOKENS, "250000")
                scene.setField(AgentLimitField.RETRIES, "0")
                scene.draw()
                assertEquals(OrganismLimits(tokens = 250_000, retries = 0), draft.limits())
                scene.setField(AgentLimitField.TOKENS, "-2")
                scene.draw()
                assertFalse(draft.valid)
                assertTrue(walk(scene.field(AgentLimitField.TOKENS)).any {
                    it.config.getOrNull(SemanticsProperties.Error) != null
                }, "Invalid input must expose an accessible error")
                scene.render(180_000_000L).use { image ->
                    File(output, "invalid-$width-$scale.png").writeBytes(image.encodeToData()!!.use { it.bytes })
                }
            }
        }
    }

    private fun ImageComposeScene.field(field: AgentLimitField): SemanticsNode =
        semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.first {
            it.config.getOrNull(SemanticsProperties.TestTag) == "agent-limit.${field.name}"
        }

    private fun ImageComposeScene.setField(field: AgentLimitField, value: String) {
        val input = walk(field(field)).first { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        // Settle composition before another field action; its callback must see the new draft.
        draw()
    }

    private fun ImageComposeScene.action(label: String): SemanticsNode =
        semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && walk(node).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
            }
        }

    private fun ImageComposeScene.draw() = repeat(5) { render(it * 16_000_000L).close() }
    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
}
