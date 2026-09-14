package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.ImmunityInterventions
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ImmunityInterventionsRenderTest {
    private companion object {
        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        fun organism() = SessionOrganism("organism", "project", "root", "immunity", 1,
            sessions = listOf(SessionNode("root", SessionKind.ZYGOTE, "Корень", generation = 1),
                SessionNode("child", SessionKind.SESSION, "Дочерняя сессия", originParentId = "root", generation = 1),
                SessionNode("immunity", SessionKind.IMMUNITY, "Независимый иммунитет", generation = 1)).associateBy { it.id },
            interventions = listOf(ImmunityIntervention("proposal", "signal", "root", 1,
                setOf(ImmunityAction.STOP, ImmunityAction.DELETE_HISTORY), listOf("Процесс не подтвердил остановку"), setOf("root", "child"), 1)))
    }

    private class Screen(width: Int = 720, scale: Float = 1f) : AutoCloseable {
        val saved = mutableStateOf(organism())
        val busy = mutableStateOf(emptySet<String>())
        val actions = mutableListOf<Triple<String, ImmunityAction, Boolean>>()
        val dismissed = mutableListOf<String>()
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(width, 760) {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                PaperTheme { PaperPanel {
                    ImmunityInterventions(saved.value, busy.value,
                        { proposal, action, confirmed -> actions += Triple(proposal.id, action, confirmed) }, { dismissed += it })
                } }
            }
        } }
        init { render() }
        fun render() { repeat(16) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        fun nodes() = onUi { scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) } }
        fun hasText(value: String) = nodes().any { text(it) == value }
        fun button(label: String) = nodes().last { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
            it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
        fun click(label: String) {
            val node = button(label)
            onUi {
                val point = node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
            }
            render()
        }
        fun open() = click("Решения иммунитета. Ожидают ответа: 1")
        fun snapshot(name: String) = onUi {
            val file = File("build/reports/immunity/$name.png").apply { parentFile.mkdirs() }
            scene.render(++frame * 32_000_000L).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
        }
        override fun close() = onUi { scene.close() }
    }

    @Test fun deletionNeedsSeparateConfirmationAndNamesIndependentImmunity() {
        Screen().use { screen ->
            screen.open()
            assertTrue(screen.hasText("Процесс не подтвердил остановку"))
            screen.click("Удалить историю…")
            assertTrue(screen.actions.isEmpty())
            assertTrue(screen.hasText("Удалить историю сессий?"))
            assertTrue(screen.hasText("Независимый иммунитет"))
            screen.snapshot("delete-confirmation")
            screen.click("Отмена")
            assertTrue(screen.actions.isEmpty())
            screen.click("Удалить историю…")
            screen.click("Удалить историю")
            assertEquals(listOf(Triple("proposal", ImmunityAction.DELETE_HISTORY, true)), screen.actions)
        }
    }

    @Test fun confirmationCannotApplyToChangedGenerationAndBusyActionsCannotRepeat() {
        Screen().use { screen ->
            screen.open(); screen.click("Удалить историю…")
            onUi { screen.saved.value = screen.saved.value.copy(sessions = screen.saved.value.sessions +
                ("root" to screen.saved.value.sessions.getValue("root").copy(generation = 2))) }
            screen.render()
            assertTrue(screen.hasText("Предложение изменилось. Вернитесь к списку решений."))
            assertNotNull(screen.button("Удалить историю").config.getOrNull(SemanticsProperties.Disabled))
            screen.click("Удалить историю")
            assertTrue(screen.actions.isEmpty())
            screen.click("Отмена")
            onUi { screen.saved.value = organism(); screen.busy.value = setOf("proposal") }
            screen.render()
            assertNotNull(screen.button("Остановить").config.getOrNull(SemanticsProperties.Disabled))
            screen.click("Остановить")
            assertTrue(screen.actions.isEmpty())
        }
    }

    @Test fun uncertainActionOffersOnlyProofAndLongDetailsRemainScrollableAtLargeText() {
        Screen(width = 390, scale = 2f).use { screen ->
            screen.open()
            onUi { screen.saved.value = screen.saved.value.copy(interventions = screen.saved.value.interventions.map {
                it.copy(state = ImmunityInterventionState.UNKNOWN, action = ImmunityAction.DELETE_HISTORY,
                    confirmedAt = 2, error = "Остановка ещё не подтверждена", evidence = List(12) { "Длинное подтверждённое наблюдение $it" })
            }) }
            screen.render()
            assertTrue(screen.hasText("Результат не подтверждён"))
            assertTrue(screen.nodes().any { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null })
            assertFalse(screen.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Остановить") == true })
            val scroll = screen.nodes().last { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            onUi { assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f)) }
            screen.render()
            val proof = screen.button("Проверить результат")
            assertTrue(proof.boundsInRoot.top >= 0 && proof.boundsInRoot.bottom <= 760)
            screen.click("Проверить результат")
            assertEquals(listOf(Triple("proposal", ImmunityAction.DELETE_HISTORY, true)), screen.actions)
            screen.snapshot("uncertain-large-text")
        }
    }
}
