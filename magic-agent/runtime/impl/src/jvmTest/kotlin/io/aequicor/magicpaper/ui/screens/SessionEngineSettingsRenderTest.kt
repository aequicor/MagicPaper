package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class SessionEngineSettingsRenderTest {
    @Test fun sessionParametersChangeEngineAtNarrowWidth() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var open by mutableStateOf(false)
            var selected: CodingEngine? = null
            val session = CodingSessionUi(CodingSession("session", "project", "Task", 0, engine = CodingEngine.PI))
            ImageComposeScene(390, 700) { MagicPaperTheme {
                CodingChat(CodingProject("project", "Project", "/project", 0), session,
                    false, true, { _, _ -> }, {}, { _, _ -> }, onSessionSettings = { open = true })
                if (open) SessionEngineSettingsDialog(CodingEngine.PI, true,
                    onChange = { selected = it }, onDismiss = { open = false })
            } }.use { scene ->
                var frame = 0L
                fun render() { repeat(16) { frame += 16_000_000L; scene.render(frame).close(); runCurrent() } }
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                fun nodes() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                fun click(label: String) {
                    val action = nodes().first { node ->
                        node.config.contains(SemanticsActions.OnClick) && walk(node).any { child ->
                            child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                        }
                    }
                    assertTrue(action.config[SemanticsActions.OnClick].action?.invoke() == true)
                    render()
                }
                render()
                click("Параметры сессии")
                val output = File("build/reports/session-settings").apply { mkdirs() }
                File(output, "engine-390.png").writeBytes(scene.render(frame + 16_000_000L).use {
                    it.encodeToData()!!.use { data -> data.bytes }
                })
                click("Codex")
                click("Сменить движок")
                assertEquals(CodingEngine.CODEX, selected)
            }
        } finally { Dispatchers.resetMain() }
    }
}
