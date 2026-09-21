package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.QuarantineRecoveryState
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class SessionQuarantineRecoveryRenderTest {
    private companion object {
        const val OPEN = "Восстановление сессии: исход прерванной операции не подтверждён"
        const val VERIFY = "Сверить фактический исход прерванных операций"
        const val RESOLVE = "Снять блокировку после собственного подтверждения результата"
        const val REASON = "Неизвестный исход powershell: выполнение завершилось без подтверждённого результата"

        fun <T> onUi(block: () -> T): T {
            if (EventQueue.isDispatchThread()) return block()
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        fun organism(audit: List<SessionAuditEvent> = listOf(SessionAuditEvent("quarantine-op", "APPLICATION",
            QUARANTINE_ACTION, setOf("root"), REASON, 1))) = SessionOrganism("organism", "project", "root", null, 1,
            sessions = mapOf("root" to SessionNode("root", SessionKind.ZYGOTE, "Корень", generation = 1,
                desired = SessionDesiredState.QUARANTINE, observed = SessionObservedState.UNKNOWN)),
            audit = audit)
    }

    private class Screen(width: Int = 720, scale: Float = 1f) : AutoCloseable {
        val saved = mutableStateOf(organism())
        val recovery = mutableStateOf(QuarantineRecoveryState())
        val reconciliations = mutableListOf<Boolean>()
        private var frame = 0L
        private val scene = onUi { ImageComposeScene(width, 760) {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                PaperTheme { PaperPanel {
                    SessionQuarantineRecovery(saved.value, "root", recovery.value) { reconciliations += it }
                } }
            }
        } }
        init { render() }
        fun render() { repeat(16) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        fun nodes() = onUi { scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) } }
        fun hasText(value: String) = nodes().any { text(it).contains(value) }
        fun button(label: String) = nodes().last { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
            it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
        fun hasButton(label: String) = nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
            it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
        fun disabled(label: String) = button(label).config.getOrNull(SemanticsProperties.Disabled) != null
        fun click(label: String) {
            val node = button(label)
            onUi {
                val point = node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
            }
            render()
        }
        fun toggleCheck() {
            val node = nodes().last { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox }
            onUi {
                val point = node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, point)
                scene.sendPointerEvent(PointerEventType.Release, point)
            }
            render()
        }
        fun snapshot(name: String) = onUi {
            val file = File("build/reports/quarantine/$name.png").apply { parentFile.mkdirs() }
            scene.render(++frame * 32_000_000L).use { image -> image.encodeToData()!!.use { file.writeBytes(it.bytes) } }
        }
        override fun close() = onUi { scene.close() }
    }

    @Test fun blockedSessionOpensRecoveryWithTheUnprovenOperationAndVerification() {
        Screen().use { screen ->
            assertTrue(screen.hasText("Исход операции не подтверждён"))
            screen.snapshot("blocked")
            screen.click(OPEN)
            assertTrue(screen.hasText("Восстановление после прерванной операции"))
            assertTrue(screen.hasText(REASON))
            assertFalse(screen.hasButton(RESOLVE), "Без недоказанного исхода подтверждение человека не предлагается")
            screen.snapshot("details")
            screen.click(VERIFY)
            assertEquals(listOf(false), screen.reconciliations)
        }
    }

    @Test fun unprovenOutcomeNeedsAnExplicitHumanCheck() {
        Screen().use { screen ->
            onUi { screen.recovery.value = QuarantineRecoveryState(awaitingConfirmation = setOf("root")) }
            screen.render()
            assertTrue(screen.hasText("Исход операции не доказан"))
            screen.click(OPEN)
            assertTrue(screen.hasText("Журнал движка не подтверждает исход"))
            assertTrue(screen.disabled(RESOLVE))
            screen.click(RESOLVE)
            assertTrue(screen.reconciliations.isEmpty(), "Без подтверждения человека блокировка не снимается")
            screen.toggleCheck()
            assertFalse(screen.disabled(RESOLVE))
            screen.snapshot("confirmation")
            screen.click(RESOLVE)
            assertEquals(listOf(true), screen.reconciliations)
        }
    }

    @Test fun runningRecoveryCannotRepeatAndAResolvedSessionShowsNothing() {
        Screen().use { screen ->
            onUi { screen.recovery.value = QuarantineRecoveryState(busy = setOf("root")) }
            screen.render()
            assertTrue(screen.disabled(OPEN))
            screen.click(OPEN)
            assertTrue(screen.reconciliations.isEmpty())
            onUi {
                screen.saved.value = organism(audit = screen.saved.value.audit + SessionAuditEvent(
                    quarantineResolutionId("root", "quarantine-op"), "APPLICATION", QUARANTINE_RESOLVED_ACTION, setOf("root"),
                    "Процесс сверён с владельцем и остановлен", 2))
                screen.recovery.value = QuarantineRecoveryState()
            }
            screen.render()
            assertFalse(screen.hasText("Исход операции не подтверждён"))
            screen.snapshot("resolved")
        }
    }

    @Test fun aBlockedContinueOpensTheRecoveryDialogByItself() {
        Screen().use { screen ->
            assertFalse(screen.hasText("Восстановление после прерванной операции"))
            onUi { screen.recovery.value = QuarantineRecoveryState(awaitingConfirmation = setOf("root"), reveal = mapOf("root" to 1L)) }
            screen.render()
            assertTrue(screen.hasText("Восстановление после прерванной операции"), "Блокировка «Продолжить» сама открывает восстановление")
            assertTrue(screen.hasText(REASON))
            screen.snapshot("revealed")
        }
    }

    @Test fun narrowWindowWithLargeTextKeepsTheActionReachableAndScrollable() {
        Screen(width = 390, scale = 2f).use { screen ->
            onUi {
                screen.saved.value = organism(audit = listOf(SessionAuditEvent("quarantine-op", "APPLICATION", QUARANTINE_ACTION,
                    setOf("root"), REASON, 1)) + (1..8).map { index ->
                    SessionAuditEvent("quarantine-op-$index", "APPLICATION", QUARANTINE_ACTION, setOf("root"),
                        "Неизвестный исход операции $index", index.toLong())
                })
                screen.recovery.value = QuarantineRecoveryState(awaitingConfirmation = setOf("root"))
            }
            screen.render()
            screen.click(OPEN)
            assertTrue(screen.nodes().any { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null })
            val scroll = screen.nodes().last { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            onUi { assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f)) }
            screen.render()
            val action = screen.button(RESOLVE)
            assertTrue(action.boundsInRoot.top >= 0 && action.boundsInRoot.bottom <= 760)
            screen.snapshot("narrow-large-text")
        }
    }
}
