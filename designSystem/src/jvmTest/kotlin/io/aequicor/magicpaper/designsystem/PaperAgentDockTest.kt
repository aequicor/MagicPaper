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
 * for it (the composer disappears off-screen), and a compact list that hides a session or its
 * question.
 *
 * Renders are captured for inspection under `build/reports/agent-dock`.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperAgentDockTest {
    private companion object {
        const val NOW = 1_000_000_000L
        val sessions = listOf(
            PaperDockSession("s1", "Восстановление дочерних сессий", PaperActivityTone.WORKING,
                running = true, selected = true, statusLabel = "работает", stateSinceMillis = NOW - 211_000),
            PaperDockSession("s2", "Индикатор always-on-top", PaperActivityTone.ATTENTION,
                statusLabel = "Ждём вашего ответа", stateSinceMillis = NOW - 42_000, needsYou = true),
            PaperDockSession("s3", "Панель агента", PaperActivityTone.UNREAD,
                statusLabel = "Работа завершена · результат не прочитан", stateSinceMillis = NOW - 3_849_000),
        )
        val quiet = PaperDockSession("s4", "Старая задача", PaperActivityTone.READY, statusLabel = "ждёт запроса")
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

    @Test fun theCompactListNamesTheSessionsWithSomethingToReportAndOpensOnActivation() {
        val expanded = mutableStateOf(false)
        val frames = Frames()
        val model = busyModel.copy(sessions = sessions + quiet, workspaceTitle = "MagicPaper")
        val scene = scene(PaperAgentDockCollapsedWidth, 300.dp) {
            Dock(expanded.value, { expanded.value = it }, model)
        }
        try {
            frames.draw(scene)
            onPaperUi {
                val texts = scene.texts()
                for (session in sessions) assertTrue(session.name in texts, "The list names ${session.name}: $texts")
                assertFalse(quiet.name in texts, "A quiet session is left to the open panel's rail")
                assertTrue("MagicPaper" in texts, "The header names the workspace")
                assertTrue("3:31" in texts && "0:42" in texts && "1:04:09" in texts,
                    "Every row counts the age of its state: $texts")
                assertTrue("Ждём вашего ответа" in texts, "A row without live activity says its status")
                val header = scene.action("Открыть панель агента")
                assertTrue(header.config.getOrNull(SemanticsProperties.ContentDescription)!!
                    .any { "Восстановление дочерних сессий" in it && "работает" in it },
                    "The header's accessible name must carry the session and its status")
                assertFalse(expanded.value)
                header.config[SemanticsActions.OnClick].action!!.invoke()
            }
            frames.draw(scene)
            onPaperUi { assertTrue(expanded.value, "Activating the header must expand it without a pointer") }
            scene.capture(frames, "collapsed")
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun activatingARowOpensThatSession() {
        val expanded = mutableStateOf(false)
        val selected = mutableListOf<String>()
        val frames = Frames()
        val scene = scene(PaperAgentDockCollapsedWidth, 300.dp) {
            Dock(expanded.value, { expanded.value = it }, busyModel, onSelectSession = { selected += it })
        }
        try {
            frames.draw(scene)
            onPaperUi { scene.row("Панель агента").config[SemanticsActions.OnClick].action!!.invoke() }
            frames.draw(scene)
            onPaperUi {
                assertEquals(listOf("s3"), selected, "The row names the session it opens")
                assertTrue(expanded.value, "Activating a row opens the panel on its conversation")
            }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun overflowIsCountedInsteadOfListed() {
        val frames = Frames()
        val many = (1..8).map { index ->
            PaperDockSession("w$index", "Этап $index", PaperActivityTone.WORKING, running = true,
                stateSinceMillis = NOW - index * 1000L)
        }
        val scene = scene(PaperAgentDockCollapsedWidth, 400.dp) {
            Dock(false, {}, PaperAgentDockModel(sessions = many, statusLabel = "работает"))
        }
        try {
            frames.draw(scene)
            onPaperUi {
                val texts = scene.texts()
                assertEquals(PaperAgentDockCompactRows, many.count { it.name in texts },
                    "The list names at most $PaperAgentDockCompactRows sessions: $texts")
                assertTrue("Ещё 3" in texts, "The rest are counted in one line: $texts")
                scene.capture(frames, "collapsed-overflow")
            }
        } finally { onPaperUi { scene.close() } }
    }

    /**
     * The host sizes the compact window from the height the list reports. Anything the list
     * draws below it is cut off by the window, so every row must fit the reported height at
     * every display scale and text scale, and a longer list must report a taller window.
     */
    @Test fun theCompactListReportsTheHeightItNeedsAndFitsIt() {
        for ((density, fontScale) in listOf(1f to 1f, 1.5f to 1f, 2f to 1f, 1f to 1.6f)) {
            fun reported(model: PaperAgentDockModel): Dp {
                val heights = mutableListOf<Dp>()
                val frames = Frames()
                val probe = scene(PaperAgentDockCollapsedWidth, 700.dp, density, fontScale) {
                    Dock(false, {}, model, onCollapsedHeightChange = { heights += it })
                }
                try { frames.draw(probe) } finally { onPaperUi { probe.close() } }
                return assertNotNull(heights.lastOrNull(), "The compact list must report its height")
            }
            val short = reported(busyModel.copy(sessions = sessions.take(1)))
            val height = reported(busyModel)
            assertTrue(height > short, "Two more rows need a taller window: $short -> $height")
            val frames = Frames()
            val scene = scene(PaperAgentDockCollapsedWidth, height, density, fontScale) { Dock(false, {}, busyModel) }
            try {
                frames.draw(scene)
                onPaperUi {
                    val bottom = height.value * density
                    for (session in sessions) {
                        val bounds = scene.row(session.name).boundsInRoot
                        assertTrue(bounds.bottom <= bottom + 0.5f,
                            "${session.name} is cut off at $density/$fontScale: ${bounds.bottom} > $bottom")
                    }
                    scene.capture(frames, "collapsed-density-$density-font-$fontScale")
                }
            } finally { onPaperUi { scene.close() } }
        }
    }

    @Test fun agesReadLikeAStatusBoard() {
        assertEquals("0:00", paperDockElapsed(-5_000))
        assertEquals("0:42", paperDockElapsed(42_000))
        assertEquals("12:05", paperDockElapsed(725_000))
        assertEquals("1:04:09", paperDockElapsed(3_849_000))
        assertEquals("2 д", paperDockElapsed(2 * 86_400_000L + 5_000))
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
            onPaperUi { assertFalse(expanded.value, "The dock starts as the compact list") }
            frames.move(scene, Offset(13f, 36f))
            onPaperUi { assertTrue(expanded.value, "Hovering the list must expand it") }
            frames.move(scene, Offset(360f, 460f))
            onPaperUi { assertFalse(expanded.value, "Leaving must retract the dock to the list") }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun thePanelOpensOnTheSessionTheReaderPointsAt() {
        val expanded = mutableStateOf(false)
        val selected = mutableListOf<String>()
        val frames = Frames()
        val scene = scene(PaperAgentDockCollapsedWidth, 300.dp) {
            Dock(expanded.value, { expanded.value = it }, busyModel, onSelectSession = { selected += it })
        }
        try {
            frames.draw(scene)
            val target = onPaperUi { scene.row("Индикатор always-on-top").boundsInRoot.center }
            frames.move(scene, target)
            onPaperUi {
                assertTrue(expanded.value, "Dwelling on a row opens the panel")
                assertEquals("s2", selected.lastOrNull(), "The panel opens on the row under the pointer")
            }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun draggingTheListMovesItWithoutExplodingItUnderTheCursor() {
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
                scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 28f), type = PointerType.Mouse)
            }
            // Past the touch slop, so this is a drag and not a click.
            for (y in listOf(42f, 54f, 44f)) {
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(60f, y), type = PointerType.Mouse) }
                frames.draw(scene, 3)
            }
            onPaperUi {
                assertEquals(1, grabs.size, "A drag past the slop must report its grab point")
                assertTrue(moves.isNotEmpty(), "The host needs the pointer position to move its window")
                // The dwell has long elapsed while the pointer sat on the list: a drag must win.
                assertFalse(expanded.value, "Dragging must not expand the panel under the cursor")
            }
            onPaperUi { scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 44f), type = PointerType.Mouse) }
            frames.draw(scene)
            onPaperUi {
                assertEquals(1, releases, "Releasing ends the drag")
                assertTrue(expanded.value, "Once the drag is over, the pointer under the list opens it")
            }
        } finally { onPaperUi { scene.close() } }
    }

    /**
     * A desktop host moves its window in window units, and Compose Desktop sizes windows one unit
     * per dp. Reporting pixels would double every move on a 2x display, and the window would
     * oscillate between two places under the cursor.
     */
    @Test fun dragPositionsAreReportedInDp() {
        val grabs = mutableListOf<Offset>()
        val moves = mutableListOf<Offset>()
        val frames = Frames()
        val scene = scene(PaperAgentDockCollapsedWidth, 300.dp, density = 2f) {
            Dock(false, {}, busyModel, expandDelayMillis = 60_000L,
                onDragStart = { x, y -> grabs.add(Offset(x, y)) },
                onDragBy = { x, y -> moves.add(Offset(x, y)) })
        }
        try {
            frames.draw(scene)
            onPaperUi { scene.sendPointerEvent(PointerEventType.Press, Offset(120f, 56f), type = PointerType.Mouse) }
            for (y in listOf(90f, 120f)) {
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(120f, y), type = PointerType.Mouse) }
                frames.draw(scene, 2)
            }
            onPaperUi {
                assertEquals(60f, grabs.single().x, 0.5f, "The grab point is in dp, not pixels")
                assertEquals(60f, moves.last().y, 0.5f, "The pointer position is in dp, not pixels")
                scene.sendPointerEvent(PointerEventType.Release, Offset(120f, 120f), type = PointerType.Mouse)
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

    @Test fun theListCarriesOneGlanceableNumberAndRowsCarryTheirLiveValue() {
        val frames = Frames()
        val model = busyModel.copy(
            attentionCount = 2,
            sessions = sessions.map { it.copy(activityLabel = if (it.id == "s1") "Читает SessionOrganismStore.kt" else it.activityLabel) },
            pendingQuestion = "double jump or wall climb?",
        )
        val collapsed = scene(PaperAgentDockCollapsedWidth, 300.dp) {
            Dock(false, {}, model)
        }
        try {
            frames.draw(collapsed)
            onPaperUi {
                val strings = collapsed.strings()
                assertTrue(strings.any { it == "2" }, "The list's header shows how many sessions need the reader")
                assertTrue(strings.any { "Читает SessionOrganismStore.kt" in it },
                    "A compact row carries what its session is doing right now")
                collapsed.capture(frames, "collapsed-badge")
            }
        } finally { onPaperUi { collapsed.close() } }
        val expanded = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
            Dock(true, {}, model)
        }
        try {
            frames.draw(expanded)
            onPaperUi {
                val strings = expanded.strings()
                assertTrue(strings.any { "Читает SessionOrganismStore.kt" in it },
                    "A row carries what its session is doing right now")
                assertTrue(strings.any { "3:31" in it }, "The header carries the age of the session's state")
                assertTrue(strings.any { it == "Ждёт вашего ответа" }, "The pending question sits above the chat")
                assertTrue(strings.any { "double jump or wall climb?" in it })
                expanded.capture(frames, "expanded-anatomy")
            }
        } finally { onPaperUi { expanded.close() } }
    }

    @Test fun theTranscriptMirrorsTheWindowKindsSystemNoticeReasoningToolCallsAndVerification() {
        val frames = Frames()
        val model = PaperAgentDockModel(
            sessions = sessions,
            statusLabel = "работает",
            transcriptKey = "s1",
            messages = listOf(
                PaperDockMessage("n1", PaperDockAuthor.AGENT, "Результат влит в main", systemNotice = true),
                PaperDockMessage("m1", PaperDockAuthor.AGENT, "", steps = listOf(
                    PaperDockStep("t1", PaperDockStepKind.THINKING, "Думаю о порядке фаз"),
                    PaperDockStep("t2", PaperDockStepKind.TOOL, "read AppRuntime.kt", tool = "read"),
                    PaperDockStep("t3", PaperDockStepKind.ERROR, "compile failed", ok = false),
                ), needsVerification = true),
            ),
        )
        val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) { Dock(true, {}, model) }
        try {
            frames.draw(scene)
            onPaperUi {
                val strings = scene.strings()
                assertTrue(strings.any { it == "Системное сообщение" }, "system notices keep their own surface")
                assertTrue(strings.any { "Резмышление агента" in it || it == "Размышление агента" },
                    "reasoning is a disclosure row, as in the window")
                assertTrue(strings.any { "read AppRuntime.kt" in it }, "tool calls keep their command line")
                assertTrue(strings.any { "compile failed" in it }, "a failed step stays visible")
                assertTrue(strings.any { it == "Нужна ручная проверка" }, "an unverified answer says so")
                scene.capture(frames, "expanded-kinds")
            }
        } finally { onPaperUi { scene.close() } }
    }

    @Test fun theExpandedPanelMovesByItsRailAndResizesByItsFreeEdges() {
        val frames = Frames()
        val drags = mutableListOf<Offset>()
        val widths = mutableListOf<Float>()
        val heights = mutableListOf<Float>()
        val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
            Dock(true, {}, busyModel,
                onDragBy = { x, y -> drags.add(Offset(x, y)) },
                onResizeWidthBy = { widths.add(it) },
                onResizeHeightBy = { heights.add(it) })
        }
        try {
            frames.draw(scene)
            // A drag that starts on the rail's empty area moves the whole window.
            onPaperUi { scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 300f), type = PointerType.Mouse) }
            for (y in listOf(316f, 332f)) {
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(60f, y), type = PointerType.Mouse) }
                frames.draw(scene, 2)
            }
            onPaperUi {
                assertTrue(drags.isNotEmpty(), "Dragging the rail must move the expanded panel")
                scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 332f), type = PointerType.Mouse)
            }
            // The grip on the free vertical edge resizes the width.
            onPaperUi { scene.sendPointerEvent(PointerEventType.Press, Offset(437f, 200f), type = PointerType.Mouse) }
            onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(452f, 200f), type = PointerType.Mouse) }
            frames.draw(scene, 2)
            onPaperUi {
                assertTrue(widths.any { it > 0f }, "The side grip must report a width delta")
                scene.sendPointerEvent(PointerEventType.Release, Offset(452f, 200f), type = PointerType.Mouse)
            }
            // The grip on the bottom edge resizes the height.
            onPaperUi { scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 465f), type = PointerType.Mouse) }
            onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 480f), type = PointerType.Mouse) }
            frames.draw(scene, 2)
            onPaperUi {
                assertTrue(heights.any { it > 0f }, "The bottom grip must report a height delta")
                scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 480f), type = PointerType.Mouse)
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

    @Test fun anErrorsRecoveryIsOfferedUnderItAndReportsItsStep() {
        val frames = Frames()
        val pending = mutableStateOf(false)
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        fun model(pending: Boolean) = busyModel.copy(busy = false, liveDetail = null, messages = listOf(
            PaperDockMessage("1", PaperDockAuthor.USER, "Проверь сборку."),
            PaperDockMessage("2", PaperDockAuthor.AGENT, "", failed = true, steps = listOf(
                PaperDockStep("t:0", PaperDockStepKind.ERROR, "Claude Code не авторизован.", ok = false,
                    recovery = PaperDockRecovery("Войти в Claude Code", "Подтвердите вход в браузере", pending)),
            )),
        ))
        val scene = scene(PaperAgentDockExpandedWidth, PaperAgentDockExpandedHeight) {
            Dock(true, {}, model(pending.value), onRecovery = { started += it }, onCancelRecovery = { cancelled += it })
        }
        try {
            frames.draw(scene)
            onPaperUi {
                scene.capture(frames, "expanded-recovery")
                scene.action("Войти в Claude Code").config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(listOf("t:0"), started, "Activation names the step whose recovery was chosen")
                pending.value = true
            }
            frames.draw(scene)
            onPaperUi {
                scene.capture(frames, "expanded-recovery-pending")
                assertTrue(scene.strings().any { it == "Подтвердите вход в браузере" }, "The pending flow says where to finish it")
                assertNull(scene.actions().firstOrNull { "Войти в Claude Code" in it.description() },
                    "A pending recovery cannot be started twice")
                scene.action("Отменить").config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(listOf("t:0"), cancelled)
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
        onRecovery: (String) -> Unit = {},
        onCancelRecovery: (String) -> Unit = {},
        onResizeWidthBy: (Float) -> Unit = {},
        onResizeHeightBy: (Float) -> Unit = {},
        onCollapsedHeightChange: (Dp) -> Unit = {},
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
            nowMillis = NOW,
            onCollapsedHeightChange = onCollapsedHeightChange,
            input = input,
            onInputChange = onInputChange,
            onSend = onSend,
            onStop = onStop,
            onOpenMainWindow = {},
            onSelectSession = onSelectSession,
            onRecovery = onRecovery,
            onCancelRecovery = onCancelRecovery,
            onResizeWidthBy = onResizeWidthBy,
            onResizeHeightBy = onResizeHeightBy,
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
