package io.aequicor.magicpaper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.designsystem.PaperActivityIndicator
import io.aequicor.magicpaper.designsystem.PaperActivityTone
import io.aequicor.magicpaper.designsystem.PaperAgentDock
import io.aequicor.magicpaper.designsystem.PaperAgentDockCollapsedHeight
import io.aequicor.magicpaper.designsystem.PaperAgentDockCollapsedWidth
import io.aequicor.magicpaper.designsystem.PaperAgentDockExpandedHeight
import io.aequicor.magicpaper.designsystem.PaperAgentDockExpandedWidth
import io.aequicor.magicpaper.designsystem.PaperAgentDockModel
import io.aequicor.magicpaper.designsystem.PaperDockAuthor
import io.aequicor.magicpaper.designsystem.PaperDockMessage
import io.aequicor.magicpaper.designsystem.PaperDockRecovery
import io.aequicor.magicpaper.designsystem.PaperDockSession
import io.aequicor.magicpaper.designsystem.PaperDockStep
import io.aequicor.magicpaper.designsystem.PaperDockStepKind
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.CodingRecovery
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.CodingStepKind
import io.aequicor.magicpaper.domain.sidebarTitle
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.ui.CodingService
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingState
import io.aequicor.magicpaper.ui.SettingsService
import io.aequicor.magicpaper.ui.actionLabel
import io.aequicor.magicpaper.ui.pendingLabel
import io.aequicor.magicpaper.ui.components.isVisibleInChat
import io.aequicor.magicpaper.ui.screens.activityTone
import io.aequicor.magicpaper.ui.screens.aggregateDockTone
import io.aequicor.magicpaper.ui.screens.label
import io.aequicor.magicpaper.ui.window.paperWindowFloatOnAllSpaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A free position in the usable area. Fractions survive resolution and display-scale changes. */
internal data class OverlayPlacement(val x: Float, val y: Float) {
    companion object { val Default = OverlayPlacement(0.98f, 0.78f) }
    fun normalized(): OverlayPlacement = OverlayPlacement(x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: Default.x,
        y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: Default.y)
}

internal fun compactOverlayBounds(usable: Rectangle, placement: OverlayPlacement): Rectangle {
    val width = ceil(PaperAgentDockCollapsedWidth.value).toInt().coerceIn(1, usable.width)
    val height = ceil(PaperAgentDockCollapsedHeight.value).toInt().coerceIn(1, usable.height)
    val normalized = placement.normalized()
    return Rectangle(
        usable.x + ((usable.width - width) * normalized.x).roundToInt(),
        usable.y + ((usable.height - height) * normalized.y).roundToInt(),
        width, height,
    )
}

/** Growth starts at the compact rectangle and goes into whichever side has room. */
internal fun expandedOverlayBounds(usable: Rectangle, compact: Rectangle): Rectangle {
    val width = ceil(PaperAgentDockExpandedWidth.value).toInt().coerceIn(1, usable.width)
    val height = ceil(PaperAgentDockExpandedHeight.value).toInt().coerceIn(1, usable.height)
    val right = usable.x + usable.width
    val bottom = usable.y + usable.height
    val x = (if (compact.x + width <= right) compact.x else compact.x + compact.width - width)
        .coerceIn(usable.x, right - width)
    val y = (if (compact.y + height <= bottom) compact.y else compact.y + compact.height - height)
        .coerceIn(usable.y, bottom - height)
    return Rectangle(x, y, width, height)
}

internal fun placementAt(usable: Rectangle, x: Int, y: Int): OverlayPlacement {
    val freeX = (usable.width - ceil(PaperAgentDockCollapsedWidth.value).toInt()).coerceAtLeast(1)
    val freeY = (usable.height - ceil(PaperAgentDockCollapsedHeight.value).toInt()).coerceAtLeast(1)
    return OverlayPlacement((x - usable.x).toFloat() / freeX, (y - usable.y).toFloat() / freeY).normalized()
}

internal fun dragTravelArea(areas: Collection<Rectangle>, fallback: Rectangle): Rectangle =
    areas.reduceOrNull(Rectangle::union) ?: fallback

/** Keeps the grabbed point under the pointer and leaves the window footprint untouched. */
internal fun draggedOrigin(pointer: Point, grab: Point, size: Dimension, travel: Rectangle): Point = Point(
    (pointer.x - grab.x).coerceIn(travel.x,
        (travel.x + travel.width - size.width).coerceAtLeast(travel.x)),
    (pointer.y - grab.y).coerceIn(travel.y,
        (travel.y + travel.height - size.height).coerceAtLeast(travel.y)),
)

internal fun interpolateRect(from: Rectangle, to: Rectangle, fraction: Double): Rectangle {
    val t = fraction.coerceIn(0.0, 1.0)
    fun lerp(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
    return Rectangle(lerp(from.x, to.x), lerp(from.y, to.y),
        lerp(from.width, to.width), lerp(from.height, to.height))
}

internal const val DOCK_MORPH_NANOS: Long = 220_000_000L
internal fun boundsProgress(elapsedNanos: Long): Double =
    (elapsedNanos.toDouble() / DOCK_MORPH_NANOS).coerceIn(0.0, 1.0)

internal fun easeOut(t: Double): Double {
    val left = 1.0 - t.coerceIn(0.0, 1.0)
    return 1.0 - left * left * left
}

internal enum class DockVisibility { HIDDEN, SHOW_COLLAPSED, KEEP }
internal fun dockVisibility(wanted: Boolean, ownerForeground: Boolean, windowCreated: Boolean,
    windowVisible: Boolean): DockVisibility = when {
    !wanted || ownerForeground -> DockVisibility.HIDDEN
    !windowCreated || !windowVisible -> DockVisibility.SHOW_COLLAPSED
    else -> DockVisibility.KEEP
}

/** Native window and service adapter for the floating Paper surface. The service owns all runs. */
internal class DesktopAgentPanel(
    private val owner: Window,
    private val coding: CodingService,
    private val settings: SettingsService,
    private val placementStore: KeyValueStore,
) : AutoCloseable {
    private companion object {
        const val PLACEMENT_KEY = "desktop.agentDock.placement"
        const val MORPH_FRAME_MS = 16
        val attentionStatuses = setOf(CodingSessionStatus.WAITING, CodingSessionStatus.CONFIRMATION,
            CodingSessionStatus.BLOCKED, CodingSessionStatus.UNREAD)
        val railOrder = listOf(CodingSessionStatus.WAITING, CodingSessionStatus.CONFIRMATION,
            CodingSessionStatus.BLOCKED, CodingSessionStatus.WORKING, CodingSessionStatus.UNREAD,
            CodingSessionStatus.NEEDS_TESTING, CodingSessionStatus.QUEUED,
            CodingSessionStatus.SCHEDULED, CodingSessionStatus.IDLE)
    }

    private data class Snapshot(
        val model: PaperAgentDockModel,
        val tone: PaperActivityTone,
        val toneLabel: String,
        val running: Boolean,
        val sessionId: String,
        val animate: Boolean,
        val recoveries: Map<String, CodingRecovery>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val snapshot = mutableStateOf<Snapshot?>(null)
    private val expanded = mutableStateOf(false)
    private val surfaceGeneration = mutableStateOf(0)
    private val drafts = mutableStateMapOf<String, String>()
    private val operationError = mutableStateOf<String?>(null)
    private val selectedSessionId = MutableStateFlow<String?>(null)
    private val updateScheduled = AtomicBoolean(false)
    private val saveVersion = AtomicLong(0)
    private val saveLock = Any()
    @Volatile private var pendingSnapshot: Snapshot? = null

    private var overlay: ComposeWindow? = null
    private var placement = OverlayPlacement.Default
    private var display: GraphicsConfiguration? = null
    private var placementLoaded = false
    private var dirtyPlacement = false
    private var dragGrab: Point? = null
    private var dragAreas: Map<GraphicsConfiguration, Rectangle> = emptyMap()
    private var morph: Timer? = null
    private var spacesRequested = false
    private var unavailable = false
    private var closed = false
    // The main window starts in front. Its native activation events control the PiP while
    // the service keeps running independently of either window's visibility.
    private var ownerForeground = true
    private val ownerListener = object : WindowAdapter() {
        override fun windowActivated(event: WindowEvent) { ownerForeground = true; applyVisibility() }
        override fun windowDeactivated(event: WindowEvent) { ownerForeground = false; applyVisibility() }
    }
    init {
        owner.addWindowListener(ownerListener)
        scope.launch(Dispatchers.IO) {
            val saved = try { placementStore.read(PLACEMENT_KEY) } catch (error: Exception) {
                AppLog.error("desktop_host", "agent.overlay.placement.read.failed", error)
                null
            }
            SwingUtilities.invokeLater {
                restorePlacement(saved)
                placementLoaded = true
                applyVisibility()
            }
        }
        scope.launch {
            combine(coding.state, settings.state, selectedSessionId) { state, settingsState, selected ->
                state.toOverlaySnapshot(settingsState.settings, selected)
            }.collect { projected ->
                pendingSnapshot = projected
                if (updateScheduled.compareAndSet(false, true)) SwingUtilities.invokeLater {
                    updateScheduled.set(false)
                    if (!closed) {
                        snapshot.value = pendingSnapshot
                        applyVisibility()
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        owner.removeWindowListener(ownerListener)
        scope.cancel()
        morph?.stop()
        morph = null
        if (dirtyPlacement) {
            saveVersion.incrementAndGet()
            synchronized(saveLock) { writePlacement(placementValue()) }
        }
        overlay?.dispose()
        overlay = null
    }

    private fun applyVisibility() {
        if (closed || !placementLoaded) return
        val wanted = snapshot.value != null
        val window = overlay
        when (dockVisibility(wanted, ownerForeground, window != null, window?.isVisible == true)) {
            DockVisibility.HIDDEN -> {
                morph?.stop()
                morph = null
                expanded.value = false
                if (window?.isVisible == true) {
                    window.isVisible = false
                    // Unmount the surface so hover, focus and keyboard-open state cannot leak
                    // into the next appearance while drafts remain with this service.
                    surfaceGeneration.value++
                }
            }
            DockVisibility.KEEP -> Unit // A streamed update must not close the conversation.
            DockVisibility.SHOW_COLLAPSED -> show()
        }
    }

    private fun show() {
        if (unavailable) return
        try {
            val window = overlay ?: dockWindow().apply { setContent { OverlayContent() } }
                .also { overlay = it }
            expanded.value = false
            window.bounds = targetBounds(window)
            window.isVisible = true
            floatOnAllSpaces(window)
            AppLog.info("desktop_host", "agent.overlay.shown", mapOf("sessions" to snapshot.value?.model?.sessions?.size.toString()))
        } catch (error: Exception) {
            unavailable = true
            overlay?.dispose()
            overlay = null
            AppLog.error("desktop_host", "agent.overlay.show.failed", error,
                mapOf("result" to "overlay_disabled"))
        }
    }

    @Composable
    private fun OverlayContent() {
        PaperTheme {
            val current = snapshot.value ?: return@PaperTheme
            val sessionId = current.sessionId
            key(surfaceGeneration.value) {
                PaperAgentDock(
                    expanded = expanded.value,
                    onExpandedChange = ::setExpanded,
                    model = current.model.copy(operationError = operationError.value),
                    indicator = { PaperActivityIndicator(current.tone, current.toneLabel,
                        running = current.running && current.animate, size = 14.dp) },
                    input = drafts[sessionId].orEmpty(),
                    onInputChange = { drafts[sessionId] = it; operationError.value = null },
                    onSend = ::send,
                    onStop = ::stop,
                    onOpenMainWindow = ::restoreOwner,
                    onSelectSession = { selectedSessionId.value = it; operationError.value = null },
                    onRecovery = { recover(it, cancel = false) },
                    onCancelRecovery = { recover(it, cancel = true) },
                    animate = current.animate,
                    onDragStart = ::dragStart,
                    onDragBy = ::dragBy,
                    onDragEnd = ::dragEnd,
                    onNudgeBy = ::nudgeBy,
                )
            }
        }
    }

    private fun setExpanded(open: Boolean) {
        if (expanded.value == open || closed) return
        expanded.value = open
        overlay?.let { animateBounds(it, targetBounds(it), snapshot.value?.animate == true) }
        AppLog.debug("desktop_host", "agent.overlay.expansion", mapOf("expanded" to open.toString()))
    }

    private fun send() {
        val current = snapshot.value ?: return
        val text = drafts[current.sessionId].orEmpty().trim()
        if (text.isEmpty() || !current.model.canSend) return
        try {
            coding.sendCodingPromptTo(current.sessionId, text)
            drafts.remove(current.sessionId)
            operationError.value = null
        } catch (error: Exception) {
            operationError.value = "Не удалось отправить. Сообщение сохранено."
            AppLog.error("desktop_host", "agent.overlay.send.failed", error,
                mapOf("sessionId" to current.sessionId, "result" to "draft_kept"))
        }
    }

    private fun stop() {
        val sessionId = snapshot.value?.sessionId ?: return
        try {
            coding.abortCodingSession(sessionId)
        } catch (error: Exception) {
            operationError.value = "Не удалось остановить. Повторите действие."
            AppLog.error("desktop_host", "agent.overlay.stop.failed", error, mapOf("sessionId" to sessionId))
        }
    }

    private fun recover(stepId: String, cancel: Boolean) {
        val recovery = snapshot.value?.recoveries?.get(stepId) ?: return
        try {
            if (cancel) coding.cancelRecovery(recovery) else coding.recover(recovery)
        } catch (error: Exception) {
            operationError.value = "Не удалось выполнить действие. Повторите попытку."
            AppLog.error("desktop_host", "agent.overlay.recovery.failed", error,
                mapOf("stepId" to stepId, "cancel" to cancel.toString()))
        }
    }

    private fun restoreOwner() {
        try {
            (owner as? Frame)?.let { it.extendedState = it.extendedState and Frame.ICONIFIED.inv() }
            owner.isVisible = true
            owner.toFront()
            owner.requestFocus()
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true)
            }
            ownerForeground = true
            applyVisibility()
        } catch (error: Exception) {
            operationError.value = "Не удалось открыть окно MagicPaper. Повторите попытку."
            AppLog.error("desktop_host", "agent.overlay.restore.failed", error)
        }
    }

    private fun targetBounds(window: Window): Rectangle {
        val usable = usableArea(configuration()) ?: return window.bounds
        val compact = compactOverlayBounds(usable, placement)
        return if (expanded.value) expandedOverlayBounds(usable, compact) else compact
    }

    private fun animateBounds(window: Window, target: Rectangle, animate: Boolean) {
        morph?.stop()
        morph = null
        val from = window.bounds
        if (!animate || from == target) { window.bounds = target; return }
        val start = System.nanoTime()
        morph = Timer(MORPH_FRAME_MS) {
            val t = boundsProgress(System.nanoTime() - start)
            window.bounds = interpolateRect(from, target, easeOut(t))
            if (t >= 1.0) { morph?.stop(); morph = null }
        }.apply { start() }
    }

    private fun configuration(): GraphicsConfiguration? = display ?: owner.graphicsConfiguration
        ?: overlay?.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration

    private fun usableArea(config: GraphicsConfiguration?): Rectangle? {
        val screen = config ?: return null
        val bounds = screen.bounds
        val insets = try { owner.toolkit.getScreenInsets(screen) } catch (error: Exception) {
            AppLog.error("desktop_host", "agent.overlay.insets.failed", error)
            return Rectangle(bounds)
        }
        return Rectangle(bounds.x + insets.left, bounds.y + insets.top,
            (bounds.width - insets.left - insets.right).coerceAtLeast(1),
            (bounds.height - insets.top - insets.bottom).coerceAtLeast(1))
    }

    private fun dragStart(x: Float, y: Float) {
        morph?.stop()
        morph = null
        dragGrab = Point(x.roundToInt(), y.roundToInt())
        dragAreas = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .map { it.defaultConfiguration }.mapNotNull { config ->
                    usableArea(config)?.let { config to it }
                }.toMap()
        } catch (error: Exception) {
            AppLog.error("desktop_host", "agent.overlay.display.lookup.failed", error)
            emptyMap()
        }
    }

    private fun dragBy(x: Float, y: Float) {
        val window = overlay ?: return
        val grab = dragGrab ?: return
        val pointer = Point(window.x + x.roundToInt(), window.y + y.roundToInt())
        dragAreas.keys.firstOrNull { it.bounds.contains(pointer) }?.let { display = it }
        val config = configuration() ?: return
        val usable = dragAreas[config] ?: usableArea(config) ?: return
        // Keep a single coordinate space while crossing displays. Clamping to the newly entered
        // screen on each move would teleport the grabbed point away from the pointer.
        val travel = dragTravelArea(dragAreas.values, usable)
        val next = draggedOrigin(pointer, grab, window.size, travel)
        window.setLocation(next)
        placement = placementAt(usable, next.x, next.y)
        dirtyPlacement = true
    }

    /** Keyboard movement uses the same free placement and screen bounds as pointer dragging. */
    private fun nudgeBy(dx: Float, dy: Float) {
        val window = overlay ?: return
        val usable = usableArea(configuration()) ?: return
        val x = (window.x + dx.roundToInt()).coerceIn(usable.x,
            (usable.x + usable.width - window.width).coerceAtLeast(usable.x))
        val y = (window.y + dy.roundToInt()).coerceIn(usable.y,
            (usable.y + usable.height - window.height).coerceAtLeast(usable.y))
        window.setLocation(x, y)
        placement = placementAt(usable, x, y)
        dirtyPlacement = true
    }

    private fun dragEnd() {
        dragGrab = null
        dragAreas = emptyMap()
        overlay?.let { window ->
            val usable = usableArea(configuration())
            if (expanded.value && usable != null &&
                (window.width > usable.width || window.height > usable.height)) {
                animateBounds(window, targetBounds(window), snapshot.value?.animate == true)
            }
        }
        if (dirtyPlacement) {
            val version = saveVersion.incrementAndGet()
            val value = placementValue()
            scope.launch(Dispatchers.IO) {
                synchronized(saveLock) {
                    if (version == saveVersion.get()) {
                        if (writePlacement(value)) SwingUtilities.invokeLater {
                            if (version == saveVersion.get()) dirtyPlacement = false
                        }
                    }
                }
            }
        }
    }

    private fun placementValue(): String {
        val value = placement.normalized()
        val displayId = display?.device?.getIDstring().orEmpty().replace("|", "")
        return "free2|$displayId|${value.x}|${value.y}"
    }

    private fun writePlacement(value: String): Boolean {
        try {
            placementStore.write(PLACEMENT_KEY, value)
            return true
        } catch (error: Exception) {
            AppLog.error("desktop_host", "agent.overlay.placement.save.failed", error)
            return false
        }
    }

    private fun restorePlacement(saved: String?) {
        if (saved == null) return
        val free = saved.split('|')
        if (free.size == 4 && free[0] == "free2") {
            val x = free[2].toFloatOrNull()
            val y = free[3].toFloatOrNull()
            if (x != null && y != null && x.isFinite() && y.isFinite()) {
                placement = OverlayPlacement(x, y).normalized()
                display = try { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                    .map { it.defaultConfiguration }.firstOrNull { it.device.getIDstring() == free[1] } }
                catch (error: Exception) {
                    AppLog.error("desktop_host", "agent.overlay.display.restore.failed", error)
                    null
                }
                return
            }
        }
        // Preserve the reader's old edge and vertical position when upgrading to free placement.
        val old = saved.split(':')
        val oldY = old.getOrNull(1)?.toFloatOrNull()
        if (old.size >= 2 && old[0] in setOf("START", "END") && oldY != null) {
            placement = OverlayPlacement(if (old[0] == "START") 0f else 1f, oldY).normalized()
        } else AppLog.error("desktop_host", "agent.overlay.placement.invalid",
            fields = mapOf("result" to "default_position"))
    }

    private fun floatOnAllSpaces(window: Window) {
        if (spacesRequested) return
        spacesRequested = true
        scope.launch {
            try {
                paperWindowFloatOnAllSpaces(window)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.error("desktop_host", "agent.overlay.spaces.failed", error,
                    mapOf("result" to "current_space_only"))
            }
        }
    }

    private fun CodingState.toOverlaySnapshot(settings: AppSettings, selectedId: String?): Snapshot? {
        val live = coding.sessions.filterNot { it.session.archived }
        if (live.none { it.status != CodingSessionStatus.IDLE }) return null
        val selected = live.firstOrNull { it.session.id == selectedId }
            ?: live.firstOrNull { it.session.id == coding.currentSessionId }
            ?: live.minBy { railOrder.indexOf(it.status) }
        val recoveries = mutableMapOf<String, CodingRecovery>()
        val canSend = selected.status !in setOf(CodingSessionStatus.WAITING,
            CodingSessionStatus.CONFIRMATION, CodingSessionStatus.BLOCKED)
        val model = PaperAgentDockModel(
            sessions = live.sortedWith(compareBy<CodingSessionUi> { railOrder.indexOf(it.status) }
                .thenByDescending { it.session.statusChangedAt }).map { session ->
                PaperDockSession(
                    id = session.session.id,
                    name = session.session.sidebarTitle(),
                    tone = session.status.activityTone,
                    running = session.running || session.draft.active,
                    selected = session.session.id == selected.session.id,
                    statusLabel = session.status.label,
                    needsYou = session.status in attentionStatuses,
                )
            },
            workspaceTitle = coding.current?.name.orEmpty(),
            statusLabel = selected.status.label,
            messages = selected.messages.takeLast(80).map { message ->
                PaperDockMessage(
                    id = message.id,
                    author = if (message.role == CodingRole.USER) PaperDockAuthor.USER else PaperDockAuthor.AGENT,
                    text = message.text.trim(),
                    systemNotice = message.systemNotice,
                    systemContext = message.systemContext,
                    failed = message.failed,
                    steps = message.steps.filter { it.isVisibleInChat(settings.hideSystemSteps) }
                        .mapIndexed { index, step ->
                            val id = step.id.ifBlank { "${message.id}:$index" }
                            step.recovery?.let { recoveries[id] = it }
                            PaperDockStep(id, when (step.kind) {
                                CodingStepKind.ANSWER -> PaperDockStepKind.ANSWER
                                CodingStepKind.THINKING -> PaperDockStepKind.THINKING
                                CodingStepKind.ERROR -> PaperDockStepKind.ERROR
                                CodingStepKind.TOOL, CodingStepKind.EXEC -> PaperDockStepKind.TOOL
                                CodingStepKind.INFO, CodingStepKind.SYSTEM, CodingStepKind.SUMMARY -> PaperDockStepKind.INFO
                            }, step.title.take(2000), step.tool, step.running, step.ok,
                                step.recovery?.let { PaperDockRecovery(it.actionLabel, it.pendingLabel,
                                    it in coding.pendingRecoveries) })
                        },
                    needsVerification = selected.completedResponseId == message.id && !selected.manuallyVerified,
                )
            }.filter { it.text.isNotEmpty() || it.steps.isNotEmpty() || it.systemContext || it.systemNotice },
            hasEarlierMessages = selected.messages.size > 80,
            liveDetail = selected.liveDetail(),
            busy = selected.running || selected.draft.active,
            canSend = canSend,
            inputPlaceholder = if (canSend) "Сообщение агенту…" else "Продолжите в окне MagicPaper",
            pendingQuestion = selected.pendingQuestion(),
            attentionCount = live.count { it.status in attentionStatuses },
            transcriptKey = selected.session.id,
        )
        val tone = aggregateDockTone(live.map { it.status.activityTone })
        return Snapshot(model, tone, when (tone) {
            PaperActivityTone.WORKING -> "Агенты работают"
            PaperActivityTone.ATTENTION -> "Нужен ответ"
            PaperActivityTone.UNREAD -> "Новые ответы"
            PaperActivityTone.NEEDS_TESTING -> "Нужна проверка"
            PaperActivityTone.QUEUED -> "В очереди"
            PaperActivityTone.READY -> "Готово"
        }, live.any { it.running || it.draft.active }, selected.session.id,
            settings.paperAnimationEnabled, recoveries)
    }

    private fun CodingSessionUi.pendingQuestion(): String? =
        interactions.firstOrNull()?.questions?.firstOrNull()?.title?.takeIf { it.isNotBlank() }

    private fun CodingSessionUi.liveDetail(): String? {
        if (!running && !draft.active) return null
        if (draft.awaitingApproval) return "Ждёт подтверждения действия"
        if (draft.awaitingModel) return "Ожидает ответа модели"
        draft.steps.lastOrNull { it.running }?.title?.takeIf { it.isNotBlank() }?.let { return it }
        draft.reasoningSummary.takeIf { it.isNotBlank() }?.let { return it }
        draft.steps.lastOrNull()?.title?.takeIf { it.isNotBlank() }?.let { return it }
        return "Прогон выполняется"
    }
}

internal const val DOCK_WINDOW_TITLE = "MagicPaper · агенты"

/** Utility window preserves native platform ownership without a taskbar or Dock duplicate. */
internal fun dockWindow(): ComposeWindow = ComposeWindow().apply {
    name = "MagicPaperAgentOverlay"
    title = DOCK_WINDOW_TITLE
    type = Window.Type.UTILITY
    rootPane.putClientProperty("Window.hidesOnDeactivate", false)
    isUndecorated = true
    isTransparent = true
    background = Color(0, 0, 0, 0)
    isAlwaysOnTop = true
    isAutoRequestFocus = false
    focusableWindowState = true
}
