package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Compose renders and semantic actions for the floating Paper surface. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperAgentDockTest {
    private val sessions = listOf(
        PaperDockSession("one", "Сборка очень длинного названия проекта", PaperActivityTone.WORKING,
            running = true, selected = true, statusLabel = "работает"),
        PaperDockSession("two", "Окно настроек", PaperActivityTone.ATTENTION,
            statusLabel = "ждёт ответа", needsYou = true),
        PaperDockSession("three", "Результат", PaperActivityTone.UNREAD, statusLabel = "новое"),
        PaperDockSession("four", "Ожидание", PaperActivityTone.QUEUED, statusLabel = "в очереди"),
    )

    private class SceneLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init { registry.currentState = Lifecycle.State.RESUMED }
    }

    @Test fun compactCardKeepsFixedFootprintAndShowsTheFirstThreeAgents() {
        val scene = scene(PaperAgentDockCollapsedWidth, PaperAgentDockCollapsedHeight, fontScale = 1.2f) {
            Dock(false, {}, PaperAgentDockModel(sessions = sessions, attentionCount = 1))
        }
        try {
            draw(scene)
            val texts = onPaperUi { scene.texts() }
            assertTrue("Сборка очень длинного названия проекта" in texts)
            assertTrue("Окно настроек" in texts)
            assertTrue("Результат" in texts)
            assertFalse("Ожидание" in texts)
            assertTrue("+1" in texts)
            capture(scene, "compact")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun largeTextKeepsAReadableCompactCardAtTheSameSize() {
        val scene = scene(PaperAgentDockCollapsedWidth, PaperAgentDockCollapsedHeight, fontScale = 2f) {
            Dock(false, {}, PaperAgentDockModel(sessions = sessions, attentionCount = 1))
        }
        try {
            draw(scene)
            val texts = onPaperUi { scene.texts() }
            assertTrue("Сборка очень длинного названия проекта" in texts)
            assertTrue("Окно настроек" in texts)
            assertFalse("Результат" in texts)
            assertTrue("+2" in texts)
            capture(scene, "large-text")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun keyboardActivationOpensTheConversationAndCanSend() {
        val expanded = mutableStateOf(false)
        var sends = 0
        var selected = ""
        val model = PaperAgentDockModel(sessions = sessions, transcriptKey = "one",
            messages = listOf(PaperDockMessage("u", PaperDockAuthor.USER, "Проверь сборку"),
                PaperDockMessage("a", PaperDockAuthor.AGENT, "Проверяю проект")), hasEarlierMessages = true)
        val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
            Dock(expanded.value, { expanded.value = it }, model, input = "Сообщение", onSend = { sends++ },
                onSelectSession = { selected = it })
        }
        try {
            draw(scene)
            onPaperUi { scene.action("Раскрыть панель агентов").config[SemanticsActions.OnClick].action!!.invoke() }
            draw(scene)
            assertTrue(onPaperUi { expanded.value })
            repeat(10) {
                if (onPaperUi { scene.texts().contains("Проверяю проект") }) return@repeat
                Thread.sleep(50)
                draw(scene)
            }
            assertTrue(onPaperUi { scene.texts().contains("Проверяю проект") },
                "The agent answer should render after the Markdown parser settles")
            assertTrue(onPaperUi { scene.action("Ранние сообщения в окне").config.contains(SemanticsActions.OnClick) })
            onPaperUi { scene.action("Окно настроек").config[SemanticsActions.OnClick].action!!.invoke() }
            assertEquals("two", selected)
            onPaperUi { scene.action("Отправить").config[SemanticsActions.OnClick].action!!.invoke() }
            assertEquals(1, sends)
            capture(scene, "conversation")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun waitingStatePreservesTheSessionListAndDisablesSendingAtNarrowWidth() {
        val model = PaperAgentDockModel(
            sessions = sessions.map { it.copy(selected = it.id == "two") },
            pendingQuestion = "Какой вариант интерфейса выбрать?", canSend = false,
            inputPlaceholder = "Продолжите в окне MagicPaper", transcriptKey = "two",
        )
        val scene = scene(400.dp, 560.dp, fontScale = 1.3f) { Dock(true, {}, model, input = "Черновик") }
        try {
            draw(scene)
            val texts = onPaperUi { scene.texts() }
            assertTrue("Окно настроек" in texts)
            assertTrue("Какой вариант интерфейса выбрать?" in texts)
            assertTrue(onPaperUi { scene.action("Отправить").config.contains(SemanticsProperties.Disabled) })
            assertTrue(onPaperUi { scene.action("Ответить в окне").config.contains(SemanticsActions.OnClick) })
            capture(scene, "narrow-waiting")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun shortDisplayKeepsTheQuestionAndWindowActionVisible() {
        val model = PaperAgentDockModel(sessions = sessions, pendingQuestion = "Нужно подтверждение",
            canSend = false, transcriptKey = "one")
        val scene = scene(380.dp, 330.dp, fontScale = 1.2f) { Dock(true, {}, model) }
        try {
            draw(scene)
            assertTrue(onPaperUi { scene.texts().contains("Нужно подтверждение") })
            assertTrue(onPaperUi { scene.action("Ответить в окне").config.contains(SemanticsActions.OnClick) })
            capture(scene, "short-display")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun failedStepOffersItsRecoveryWithoutLosingChat() {
        val message = PaperDockMessage("a", PaperDockAuthor.AGENT, "Не удалось подключиться", failed = true,
            steps = listOf(PaperDockStep("step", PaperDockStepKind.ERROR, "Движок недоступен",
                recovery = PaperDockRecovery("Войти", "Вход…"))))
        var recovered = ""
        val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
            Dock(true, {}, PaperAgentDockModel(sessions = sessions, messages = listOf(message), transcriptKey = "one"),
                onRecovery = { recovered = it })
        }
        try {
            draw(scene)
            onPaperUi { scene.action("Войти").config[SemanticsActions.OnClick].action!!.invoke() }
            assertEquals("step", recovered)
            assertTrue(onPaperUi { scene.texts().contains("Не удалось подключиться") })
            capture(scene, "recovery")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun draggingTheHeaderDoesNotExpandTheCardUnderThePointer() {
        val expanded = mutableStateOf(false)
        var starts = 0
        var moves = 0
        val scene = scene(PaperAgentDockCollapsedWidth, PaperAgentDockCollapsedHeight) {
            Dock(expanded.value, { expanded.value = it }, PaperAgentDockModel(sessions = sessions),
                onDragStart = { _, _ -> starts++ }, onDragBy = { _, _ -> moves++ })
        }
        try {
            draw(scene)
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Move, Offset(85f, 27f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Press, Offset(85f, 27f), type = PointerType.Mouse)
            }
            draw(scene)
            Thread.sleep(PaperAgentDockExpandDelayMillis + 80)
            draw(scene)
            assertFalse(onPaperUi { expanded.value }, "Holding the header must not resize it before dragging")
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Move, Offset(87f, 46f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Move, Offset(91f, 68f), type = PointerType.Mouse)
            }
            draw(scene)
            assertTrue(starts > 0 && moves > 0, "The title should be a working drag handle")
            Thread.sleep(PaperAgentDockExpandDelayMillis + 80)
            draw(scene)
            assertFalse(onPaperUi { expanded.value }, "A long drag must keep the same footprint")
            onPaperUi { scene.sendPointerEvent(PointerEventType.Release, Offset(91f, 68f), type = PointerType.Mouse) }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun dragHandleOffersKeyboardMovementWithoutResizing() {
        val expanded = mutableStateOf(false)
        val nudges = mutableListOf<Pair<Float, Float>>()
        val scene = scene(PaperAgentDockCollapsedWidth, PaperAgentDockCollapsedHeight) {
            PaperAgentDock(expanded.value, { expanded.value = it }, PaperAgentDockModel(sessions = sessions),
                indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "Агенты работают") },
                animate = false, onNudgeBy = { x, y -> nudges += x to y })
        }
        try {
            draw(scene)
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Press, Offset(88f, 28f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(88f, 28f), type = PointerType.Mouse)
            }
            draw(scene)
            assertTrue(onPaperUi { scene.texts().contains("Стрелки для перемещения") })
            onPaperUi { scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown)) }
            assertEquals(listOf(24f to 0f), nudges)
            Thread.sleep(PaperAgentDockExpandDelayMillis + 80)
            draw(scene)
            assertFalse(onPaperUi { expanded.value }, "Keyboard movement must not expand under a parked pointer")
        } finally { onPaperUi { scene.close() } }
    }

    @Composable
    private fun Dock(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, model: PaperAgentDockModel,
        input: String = "", onSend: () -> Unit = {}, onRecovery: (String) -> Unit = {},
        onDragStart: (Float, Float) -> Unit = { _, _ -> }, onDragBy: (Float, Float) -> Unit = { _, _ -> },
        onSelectSession: (String) -> Unit = {}) {
        PaperAgentDock(expanded, onExpandedChange, model,
            indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "Агенты работают", size = 14.dp) },
            input = input, onSend = onSend, onRecovery = onRecovery, animate = false,
            onDragStart = onDragStart, onDragBy = onDragBy, onSelectSession = onSelectSession)
    }

    private fun scene(width: Dp, height: Dp, fontScale: Float = 1f,
        content: @Composable () -> Unit): ImageComposeScene = onPaperUi {
        ImageComposeScene(width.value.toInt(), height.value.toInt()) {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale),
                LocalLifecycleOwner provides SceneLifecycle()) { PaperTheme { content() } }
        }
    }

    private fun draw(scene: ImageComposeScene) {
        repeat(12) { frame -> onPaperUi { scene.render(frame * 16_000_000L).close() } }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    }

    private fun ImageComposeScene.texts(): List<String> = nodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    private fun ImageComposeScene.action(label: String): SemanticsNode = nodes()
        .first { it.config.contains(SemanticsActions.OnClick) &&
            (it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { name -> label in name } ||
                label in (it.config.getOrNull(SemanticsActions.OnClick)?.label ?: "")) }

    private fun capture(scene: ImageComposeScene, name: String) {
        File("build/reports/agent-overlay/$name.png").apply { parentFile.mkdirs() }
            .writeBytes(onPaperUi { scene.render(300_000_000L).use { image ->
                image.encodeToData()!!.use { it.bytes }
            } })
    }
}
