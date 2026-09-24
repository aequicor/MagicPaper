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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        val sessions = listOf(
            PaperDockSession("s1", "Восстановление дочерних сессий", PaperActivityTone.WORKING,
                running = true, selected = true),
            PaperDockSession("s2", "Индикатор always-on-top", PaperActivityTone.ATTENTION),
            PaperDockSession("s3", "Панель агента", PaperActivityTone.UNREAD),
        )
        val busyModel = PaperAgentDockModel(
            sessions = sessions,
            statusLabel = "работает",
            busy = true,
            liveDetail = "Читает SessionOrganismStore.kt",
            transcriptKey = "s1",
            messages = listOf(
                PaperDockMessage("1", PaperDockAuthor.USER, "Почему сессии теряются после падения?"),
                PaperDockMessage("2", PaperDockAuthor.AGENT,
                    "Журнал восстанавливается **до** `CodingService.start()`.\n\n- порядок фаз в `AppRuntime`\n- `recovery` не публикуется"),
                PaperDockMessage("3", PaperDockAuthor.USER, "Исправь порядок."),
            ),
        )
        val waitingModel = busyModel.copy(
            sessions = sessions.map { it.copy(selected = it.id == "s2") },
            statusLabel = "Ждём вашего ответа", busy = false, liveDetail = null,
            canSend = false, inputPlaceholder = "Ответьте на вопрос в окне MagicPaper",
            transcriptKey = "s2",
        )
    }

    /** PaperBackground reads the lifecycle the way the application window provides it. */
    private class DockLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init { registry.currentState = Lifecycle.State.RESUMED }
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

    @Test fun draggingTheTabMovesItWithoutExplodingItUnderTheCursor() {
        val expanded = mutableStateOf(false)
        val grabs = mutableListOf<Offset>()
        val moves = mutableListOf<Offset>()
        var releases = 0
        val frames = Frames()
        val scene = scene(400.dp, 500.dp) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                Box(Modifier.size(PaperAgentDockCollapsedWidth, PaperAgentDockCollapsedHeight)) {
                    Dock(expanded.value, { expanded.value = it }, busyModel,
                        expandDelayMillis = 0L, collapseDelayMillis = 0L,
                        onDragStart = { x, y -> grabs.add(Offset(x, y)) },
                        onDragBy = { x, y -> moves.add(Offset(x, y)) },
                        onDragEnd = { releases++ })
                }
            }
        }
        try {
            frames.draw(scene)
            onPaperUi {
                scene.sendPointerEvent(PointerEventType.Press, Offset(21f, 44f), type = PointerType.Mouse)
            }
            // Past the touch slop, so this is a drag and not a click.
            for (y in listOf(58f, 70f, 60f)) {
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(21f, y), type = PointerType.Mouse) }
                frames.draw(scene, 3)
            }
            onPaperUi {
                assertEquals(1, grabs.size, "A drag past the slop must report its grab point")
                assertTrue(moves.isNotEmpty(), "The host needs the pointer position to move its window")
                // The dwell has long elapsed while the pointer sat on the tab: a drag must win.
                assertFalse(expanded.value, "Dragging must not expand the panel under the cursor")
            }
            onPaperUi { scene.sendPointerEvent(PointerEventType.Release, Offset(21f, 60f), type = PointerType.Mouse) }
            frames.draw(scene)
            onPaperUi {
                assertEquals(1, releases, "Releasing ends the drag")
                assertTrue(expanded.value, "Once the drag is over, the pointer under the tab opens it")
            }
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

    @Test fun theSessionRailListsEverySessionAndSelectsOnActivation() {
        val frames = Frames()
        val selected = mutableListOf<String>()
        val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
            Dock(true, {}, busyModel, onSelectSession = { selected.add(it) })
        }
        try {
            frames.draw(scene)
            onPaperUi {
                for (session in sessions) {
                    assertTrue(scene.strings().any { it == session.name }, "The rail lists ${session.name}")
                }
                assertTrue(scene.row("Восстановление дочерних сессий")
                    .config.getOrNull(SemanticsProperties.Selected) == true,
                    "The session the dock chats with is marked selected")
                scene.row("Индикатор always-on-top").config[SemanticsActions.OnClick].action!!.invoke()
            }
            frames.draw(scene)
            onPaperUi {
                assertEquals(listOf("s2"), selected, "Activating a row selects that session")
                scene.capture(frames, "expanded-rail")
            }
        } finally { onPaperUi { scene.close() } }
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
            Dock(true, {}, PaperAgentDockModel(statusLabel = "ждёт запроса"))
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
        onSelectSession: (String) -> Unit = {},
        onDragStart: (Float, Float) -> Unit = { _, _ -> },
        onDragBy: (Float, Float) -> Unit = { _, _ -> },
        onDragEnd: () -> Unit = {},
        expandDelayMillis: Long = 0L,
        collapseDelayMillis: Long = 0L,
    ) {
        PaperAgentDock(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            model = model,
            indicator = {
                PaperActivityIndicator(PaperActivityTone.WORKING, model.statusLabel,
                    running = model.busy, size = 14.dp)
            },
            input = input,
            onInputChange = onInputChange,
            onSend = onSend,
            onStop = onStop,
            onOpenMainWindow = {},
            onSelectSession = onSelectSession,
            onDragStart = onDragStart,
            onDragBy = onDragBy,
            onDragEnd = onDragEnd,
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
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalLifecycleOwner provides DockLifecycleOwner(),
            ) {
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

    /** A rail row is the clickable ancestor of its title, in the unmerged tree. */
    private fun ImageComposeScene.row(name: String): SemanticsNode = nodes()
        .filter { it.config.contains(SemanticsActions.OnClick) }
        .single { row ->
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            walk(row).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == name } }
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
