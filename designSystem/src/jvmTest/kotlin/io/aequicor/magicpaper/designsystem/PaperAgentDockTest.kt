package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dock is the only view of a run while the application window is away, so two failures are
 * user-visible bugs rather than cosmetics: a panel that does not fit the window its host sized
 * for it (the composer disappears off-screen), and a collapsed tab that renders a squeezed
 * session title beside the indicator.
 *
 * Renders are captured for inspection under `build/reports/agent-dock`.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperAgentDockTest {
    private companion object {
        val busyModel = PaperAgentDockModel(
            statusLabel = "работает",
            sessionLabel = "Восстановление дочерних сессий после сбоя",
            busy = true,
            liveDetail = "Читает SessionOrganismStore.kt",
            transcriptKey = "session-1",
            messages = listOf(
                PaperDockMessage("1", PaperDockAuthor.USER, "Почему сессии теряются после падения?"),
                PaperDockMessage("2", PaperDockAuthor.AGENT,
                    "Журнал восстанавливается **до** `CodingService.start()`.\n\n- порядок фаз в `AppRuntime`\n- `recovery` не публикуется"),
                PaperDockMessage("3", PaperDockAuthor.USER, "Исправь порядок."),
            ),
        )
        val waitingModel = busyModel.copy(
            statusLabel = "Ждём вашего ответа", busy = false, liveDetail = null,
            canSend = false, inputPlaceholder = "Ответьте на вопрос в окне MagicPaper",
        )
    }

    @Test fun collapsedTabCarriesTheIndicatorAloneAndExpandsOnActivation() {
        val expanded = mutableStateOf(false)
        val frames = Frames()
        val scene = scene(PaperAgentDockCollapsedWidth, PaperAgentDockCollapsedHeight) {
            Dock(expanded.value, { expanded.value = it }, busyModel)
        }
        try {
            frames.draw(scene)
            onPaperUi {
                val texts = scene.texts()
                assertTrue(texts.isEmpty(), "A collapsed tab must not render a title beside the dot: $texts")
                val tab = scene.action("Открыть панель агента")
                assertTrue(tab.config.getOrNull(SemanticsProperties.ContentDescription)!!
                    .any { "Восстановление дочерних сессий" in it && "работает" in it },
                    "The tab's accessible name must carry the session and its status")
                assertFalse(expanded.value)
                tab.config[SemanticsActions.OnClick].action!!.invoke()
            }
            frames.draw(scene)
            onPaperUi { assertTrue(expanded.value, "Activating the tab must expand it without a pointer") }
            scene.capture(frames, "collapsed")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun hoverOpensThePanelAndLeavingRetractsIt() {
        val expanded = mutableStateOf(false)
        val frames = Frames()
        // A scene larger than the tab, so the pointer can leave the dock without leaving the window.
        val scene = scene(400.dp, 500.dp) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                Box(Modifier.size(PaperAgentDockCollapsedWidth, PaperAgentDockCollapsedHeight)) {
                    Dock(expanded.value, { expanded.value = it }, busyModel,
                        expandDelayMillis = 0L, collapseDelayMillis = 0L)
                }
            }
        }
        try {
            frames.draw(scene)
            onPaperUi { assertFalse(expanded.value, "The dock starts as a tab") }
            frames.move(scene, Offset(13f, 36f))
            onPaperUi { assertTrue(expanded.value, "Hovering the tab must expand it") }
            frames.move(scene, Offset(320f, 320f))
            onPaperUi { assertFalse(expanded.value, "Leaving must retract the dock to the tab") }
        } finally { onPaperUi { scene.close() } }
    }

    /**
     * The dwell is wall-clock time, which a headless scene cannot advance deterministically, so
     * the contract is asserted on the published constants: they must be a real dwell, otherwise a
     * pointer crossing the screen edge throws a 360 dp panel over the reader's other work.
     */
    @Test fun hoverDwellOutlivesAPassingPointer() {
        assertTrue(PaperAgentDockExpandDelayMillis >= 150L,
            "Opening needs a dwell: $PaperAgentDockExpandDelayMillis")
        assertTrue(PaperAgentDockCollapseDelayMillis > PaperAgentDockExpandDelayMillis,
            "Retracting must be slower than opening: $PaperAgentDockCollapseDelayMillis")
    }

    @Test fun escapeCollapsesTheExpandedPanel() {
        val expanded = mutableStateOf(true)
        val frames = Frames()
        val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
            Dock(expanded.value, { expanded.value = it }, busyModel)
        }
        try {
            frames.draw(scene)
            onPaperUi {
                // Escape reaches the panel through whichever control holds focus.
                scene.action("Свернуть панель").config[SemanticsActions.RequestFocus].action!!.invoke()
            }
            frames.draw(scene)
            onPaperUi {
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
            }
            frames.draw(scene)
            onPaperUi { assertFalse(expanded.value, "Escape must retract the panel") }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun stopBelongsToABusyRunAndAnEmptyComposerCannotSend() {
        for ((model, expectStop) in listOf(busyModel to true, waitingModel to false)) {
            val frames = Frames()
            val sent = mutableListOf<Int>()
            var stopped = 0
            val input = mutableStateOf("")
            val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
                Dock(true, {}, model, input = input.value,
                    onInputChange = { input.value = it },
                    onSend = { sent.add(1) }, onStop = { stopped++ })
            }
            try {
                frames.draw(scene)
                onPaperUi {
                    assertEquals(expectStop, scene.actions().any { "Остановить прогон" in it.description() },
                        "Stop is offered only while a run is in progress")
                    assertTrue(scene.action("Отправить сообщение").isDisabled(),
                        "An empty composer must not send")
                    input.value = "продолжай"
                }
                frames.draw(scene)
                onPaperUi {
                    val send = scene.action("Отправить сообщение")
                    if (model.canSend) {
                        assertFalse(send.isDisabled(), "A non-empty composer sends")
                        send.config[SemanticsActions.OnClick].action!!.invoke()
                    } else {
                        assertTrue(send.isDisabled(), "A composer the run cannot accept stays disabled")
                        assertTrue(scene.strings().any { "Ответьте на вопрос в окне MagicPaper" in it },
                            "An unavailable composer must say why instead of inviting lost input")
                    }
                    scene.capture(frames, if (expectStop) "expanded-busy" else "expanded-waiting")
                }
                frames.draw(scene)
                onPaperUi {
                    assertEquals(if (model.canSend) 1 else 0, sent.size)
                    if (expectStop) {
                        scene.action("Остановить прогон").config[SemanticsActions.OnClick].action!!.invoke()
                        assertEquals(1, stopped)
                    }
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    /**
     * The host sizes its window from the published dp constants, so anything the dock draws
     * outside them is clipped by the window and simply gone: at 150 % scaling that took the
     * composer with it. Large text is checked the same way, since it grows every row.
     */
    @Test fun everyControlFitsTheWindowTheHostSizesForIt() {
        for ((density, fontScale) in listOf(1f to 1f, 1.5f to 1f, 2f to 1f, 1f to 1.6f)) {
            val frames = Frames()
            val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight,
                density = density, fontScale = fontScale) { Dock(true, {}, busyModel) }
            try {
                frames.draw(scene)
                onPaperUi {
                    val sceneWidth = PaperAgentDockExpandedWidth.value * density
                    val sceneHeight = PaperAgentDockExpandedHeight.value * density
                    for (label in listOf("Отправить сообщение", "Остановить прогон",
                        "Открыть окно MagicPaper", "Свернуть панель")) {
                        val bounds = scene.action(label).boundsInRoot
                        assertTrue(bounds.left >= 0f && bounds.top >= 0f, "$label starts inside the window")
                        assertTrue(bounds.right <= sceneWidth + 0.5f,
                            "$label is clipped horizontally at $density/$fontScale: ${bounds.right} > $sceneWidth")
                        assertTrue(bounds.bottom <= sceneHeight + 0.5f,
                            "$label is clipped vertically at $density/$fontScale: ${bounds.bottom} > $sceneHeight")
                    }
                    assertNotNull(scene.texts().firstOrNull { "Читает SessionOrganismStore.kt" in it },
                        "The live step must stay visible while the run works")
                    scene.capture(frames, "expanded-density-$density-font-$fontScale")
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun anEmptyTranscriptSaysSoInsteadOfShowingABlankArea() {
        val frames = Frames()
        val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
            Dock(true, {}, PaperAgentDockModel(statusLabel = "ждёт запроса", sessionLabel = "Новая сессия"))
        }
        try {
            frames.draw(scene)
            onPaperUi {
                assertTrue(scene.texts().any { it == "Пока нет сообщений" })
                assertNull(scene.actions().firstOrNull { "Остановить прогон" in it.description() })
                scene.capture(frames, "expanded-empty")
            }
        } finally { onPaperUi { scene.close() } }
    }

    @Composable
    private fun Dock(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        model: PaperAgentDockModel,
        input: String = "",
        onInputChange: (String) -> Unit = {},
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
        expandDelayMillis: Long = 0L,
        collapseDelayMillis: Long = 0L,
    ) {
        PaperAgentDock(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            model = model,
            dockedToStart = true,
            indicator = {
                PaperActivityIndicator(PaperActivityTone.WORKING, model.statusLabel,
                    running = model.busy, size = 14.dp)
            },
            input = input,
            onInputChange = onInputChange,
            onSend = onSend,
            onStop = onStop,
            onOpenMainWindow = {},
            expandDelayMillis = expandDelayMillis,
            collapseDelayMillis = collapseDelayMillis,
        )
    }

    private fun scene(
        width: Dp,
        height: Dp,
        density: Float = 1f,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ): ImageComposeScene = onPaperUi {
        ImageComposeScene(
            (width.value * density).toInt().coerceAtLeast(1),
            (height.value * density).toInt().coerceAtLeast(1),
        ) {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                PaperTheme { content() }
            }
        }
    }

    /** One scene clock, so a test controls how many frames its assertions see. */
    private class Frames {
        var now = 0L
            private set

        fun draw(scene: ImageComposeScene, count: Int = 8) {
            repeat(count) { onPaperUi { scene.render(now).close() }; now += 16_000_000L }
        }

        fun move(scene: ImageComposeScene, point: Offset) {
            onPaperUi { scene.sendPointerEvent(PointerEventType.Move, point, type = PointerType.Mouse) }
            draw(scene, 4)
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun ImageComposeScene.actions() = nodes().filter { it.config.contains(SemanticsActions.OnClick) }

    private fun ImageComposeScene.action(label: String) = actions().single { label in it.description() }

    private fun ImageComposeScene.texts() = nodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        .map { it.text }

    /** Everything a reader can perceive or an assistive technology can announce. */
    private fun ImageComposeScene.strings() = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    /** A control is found by its accessible name or by the label of its click action. */
    private fun SemanticsNode.description(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsActions.OnClick)?.label)).joinToString()

    private fun SemanticsNode.isDisabled() = config.contains(SemanticsProperties.Disabled)

    private fun ImageComposeScene.capture(frames: Frames, name: String) {
        File("build/reports/agent-dock/$name.png").apply { parentFile.mkdirs() }
            .writeBytes(onPaperUi { render(frames.now).use { it.encodeToData()!!.use { data -> data.bytes } } })
    }
}
