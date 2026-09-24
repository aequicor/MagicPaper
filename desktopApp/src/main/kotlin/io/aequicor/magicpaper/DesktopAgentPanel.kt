package io.aequicor.magicpaper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.designsystem.PaperActivityTone
import io.aequicor.magicpaper.designsystem.PaperActivityIndicator
import io.aequicor.magicpaper.designsystem.PaperAgentDock
import io.aequicor.magicpaper.designsystem.PaperAgentDockCollapsedHeight
import io.aequicor.magicpaper.designsystem.PaperAgentDockCollapsedWidth
import io.aequicor.magicpaper.designsystem.PaperAgentDockExpandedHeight
import io.aequicor.magicpaper.designsystem.PaperAgentDockExpandedWidth
import io.aequicor.magicpaper.designsystem.PaperAgentDockMinSize
import io.aequicor.magicpaper.designsystem.PaperAgentDockModel
import io.aequicor.magicpaper.designsystem.PaperDockAuthor
import io.aequicor.magicpaper.designsystem.PaperAgentDockShadowMargin
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
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.ui.CodingService
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingState
import io.aequicor.magicpaper.ui.actionLabel
import io.aequicor.magicpaper.ui.pendingLabel
import io.aequicor.magicpaper.ui.SettingsService
import io.aequicor.magicpaper.ui.screens.activityTone
import io.aequicor.magicpaper.ui.screens.aggregateDockTone
import io.aequicor.magicpaper.ui.screens.label
import io.aequicor.magicpaper.ui.components.isVisibleInChat
import io.aequicor.magicpaper.domain.sidebarTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Desktop
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Which screen edge the dock hugs; the panel grows away from it. */
internal enum class DockEdge { START, END }

/**
 * Host-only window geometry for the docked agent panel.
 *
 * The panel is the reader's view of a run while MagicPaper itself is away: it appears when the
 * main window is minimized *or* has lost focus, and only while some session still needs the
 * reader or is working. Idle sessions stay hidden so the screen edge is not covered for nothing.
 *
 * This class owns the AWT window, its size in device pixels and its remembered place on the
 * edge. It never owns the run: sending, stopping and reading state belong to [CodingService],
 * and every visual decision belongs to [PaperAgentDock].
 */
internal class DesktopAgentPanel(
    private val owner: Window,
    private val coding: CodingService,
    private val settings: SettingsService,
    private val placement: KeyValueStore,
) : AutoCloseable {
    private companion object {
        const val PLACEMENT_KEY = "desktop.agentDock.placement"
        const val DEFAULT_OFFSET = 0.34f
        /** The dock shows a recent window of the transcript, not the whole journal. */
        const val MAX_MESSAGES = 40
        const val MAX_MESSAGE_CHARS = 2000
        /**
         * Sessions that need the reader: they sort to the top of the rail and feed the tab's
         * badge, the way an operator board puts blocked work first.
         */
        val NEEDS_YOU = setOf(
            CodingSessionStatus.WAITING,
            CodingSessionStatus.CONFIRMATION,
            CodingSessionStatus.BLOCKED,
        )
        val RAIL_ORDER = listOf(
            CodingSessionStatus.WAITING, CodingSessionStatus.CONFIRMATION, CodingSessionStatus.BLOCKED,
            CodingSessionStatus.WORKING, CodingSessionStatus.UNREAD, CodingSessionStatus.NEEDS_TESTING,
            CodingSessionStatus.QUEUED, CodingSessionStatus.SCHEDULED, CodingSessionStatus.IDLE,
        )
        /** Horizontal margin that must be crossed before the panel re-anchors to the other edge. */
        const val EDGE_HYSTERESIS = 48
        /** The expand/collapse morph: short enough to feel instant, long enough to read. */
        const val BOUNDS_DURATION_MILLIS = 200_000_000.0
        const val BOUNDS_STEP_MILLIS = 16
        /** Most urgent first: the dock shows one session, so it shows the one that needs the reader. */
        val URGENCY = listOf(
            CodingSessionStatus.WORKING,
            CodingSessionStatus.WAITING,
            CodingSessionStatus.CONFIRMATION,
            CodingSessionStatus.BLOCKED,
            CodingSessionStatus.UNREAD,
            CodingSessionStatus.NEEDS_TESTING,
            CodingSessionStatus.QUEUED,
            CodingSessionStatus.SCHEDULED,
        )
    }

    /** What the dock shows; null hides it. One write per state change keeps invalidation cheap. */
    private data class Snapshot(
        val model: PaperAgentDockModel,
        val tone: PaperActivityTone,
        val toneLabel: String,
        val anyRunning: Boolean,
        val sessionId: String,
        val animate: Boolean,
        /** The failure actions behind the dock's step ids, so an activation names what to perform. */
        val recoveries: Map<String, CodingRecovery> = emptyMap(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val snapshot = mutableStateOf<Snapshot?>(null)
    private val expanded = mutableStateOf(false)
    private val input = mutableStateOf("")
    private val edge = mutableStateOf(DockEdge.START)
    /** The reader's own size for the expanded card, remembered with the place on the edge. */
    private val expandedWidth = mutableStateOf(PaperAgentDockExpandedWidth)
    private val expandedHeight = mutableStateOf(PaperAgentDockExpandedHeight)
    /**
     * The session the dock reads and writes. It is the dock's own choice: selecting a row here
     * must not move the main window's navigation behind the reader's back.
     */
    private val selectedSessionId = MutableStateFlow<String?>(null)

    private var overlay: ComposeWindow? = null
    private var boundsAnimation: javax.swing.Timer? = null
    /** Pointer offset from the window origin while dragging, in device pixels. */
    private var grab: Point? = null
    /** Fraction of the free edge height above the panel, so the place survives a resolution change. */
    private var offset = DEFAULT_OFFSET
    private var ownerMinimized = false
    private var ownerFocused = true
    /** A failed overlay must not be retried on every state change. */
    private var unavailable = false

    private val stateListener = WindowStateListener { event ->
        ownerMinimized = event.newState and Frame.ICONIFIED != 0
        applyVisibility()
    }
    private val focusListener = object : WindowFocusListener {
        override fun windowGainedFocus(event: WindowEvent) { ownerFocused = true; applyVisibility() }
        override fun windowLostFocus(event: WindowEvent) { ownerFocused = false; applyVisibility() }
    }

    init {
        restorePlacement()
        ownerMinimized = (owner as? Frame)?.let { it.extendedState and Frame.ICONIFIED != 0 } ?: false
        ownerFocused = owner.isFocused
        owner.addWindowStateListener(stateListener)
        owner.addWindowFocusListener(focusListener)
        scope.launch {
            combine(coding.state, settings.state, selectedSessionId) { state, settingsState, selected ->
                Triple(state, settingsState.settings, selected)
            }.collect { (state, settings, selected) ->
                // Selecting a row in the rail must rebuild the chat, not wait for the next
                // run event: the selection is one of the combine's sources.
                snapshot.value = state.dockSnapshot(settings, selected)
                // Window visibility is an AWT decision and belongs on the EDT.
                SwingUtilities.invokeLater(::applyVisibility)
            }
        }
        applyVisibility()
    }

    override fun close() {
        scope.cancel()
        boundsAnimation?.stop()
        boundsAnimation = null
        owner.removeWindowStateListener(stateListener)
        owner.removeWindowFocusListener(focusListener)
        overlay?.dispose()
        overlay = null
    }

    // region visibility

    private fun applyVisibility() {
        val wanted = snapshot.value != null && (ownerMinimized || !ownerFocused)
        if (!wanted) {
            overlay?.isVisible = false
            return
        }
        show()
    }

    private fun show() {
        if (unavailable) return
        try {
            val window = overlay ?: createOverlay().also { overlay = it }
            // Every appearance starts as the narrow tab: the panel grows only under the pointer.
            expanded.value = false
            applyGeometry(window, animate = false)
            if (!window.isVisible) {
                window.isVisible = true
                AppLog.info("desktop_host", "agent.dock.shown", mapOf(
                    "tone" to snapshot.value?.tone?.name.orEmpty(),
                    "edge" to edge.value.name,
                ))
            }
        } catch (error: Exception) {
            unavailable = true
            overlay?.dispose()
            overlay = null
            AppLog.error("desktop_host", "agent.dock.show.failed", error,
                mapOf("result" to "dock_disabled"))
        }
    }

    private fun createOverlay(): ComposeWindow = ComposeWindow().apply {
        name = "MagicPaperAgentDock"
        title = "MagicPaper · агент"
        isUndecorated = true
        isTransparent = true
        background = Color(0, 0, 0, 0)
        isAlwaysOnTop = true
        // Appearing beside another application must not take focus from it; the composer
        // takes focus only when the reader clicks into it.
        isAutoRequestFocus = false
        focusableWindowState = true
        setContent { DockSurface() }
    }

    @Composable
    private fun DockSurface() {
        PaperTheme {
            val current = snapshot.value ?: return@PaperTheme
            PaperAgentDock(
                expanded = expanded.value,
                onExpandedChange = ::setExpanded,
                model = current.model,
                // One dot for the workspace: the most demanding session sets its colour.
                indicator = {
                    PaperActivityIndicator(current.tone, current.toneLabel, running = current.anyRunning, size = 16.dp)
                },
                input = input.value,
                onInputChange = { input.value = it },
                onSend = ::send,
                onStop = ::stop,
                onOpenMainWindow = ::restoreOwner,
                onSelectSession = { selectedSessionId.value = it },
                onRecovery = { recover(it, cancel = false) },
                onCancelRecovery = { recover(it, cancel = true) },
                onResizeWidthBy = ::resizeWidthBy,
                onResizeHeightBy = ::resizeHeightBy,
                animateBackground = current.animate,
                dockedToStart = edge.value == DockEdge.START,
                onDragStart = ::dragStart,
                onDragBy = ::dragBy,
                onDragEnd = ::dragEnd,
            )
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded.value == value) return
        expanded.value = value
        overlay?.let { applyGeometry(it, animate = snapshot.value?.animate == true) }
        AppLog.debug("desktop_host", "agent.dock.expansion", mapOf("expanded" to value.toString()))
    }

    // endregion

    // region actions

    private fun send() {
        val current = snapshot.value ?: return
        val text = input.value.trim()
        if (text.isEmpty() || !current.model.canSend) return
        input.value = ""
        try {
            coding.sendCodingPromptTo(current.sessionId, text)
        } catch (error: Exception) {
            // A rejected send must not lose what the reader typed.
            input.value = text
            AppLog.error("desktop_host", "agent.dock.send.failed", error,
                mapOf("result" to "draft_restored"))
        }
    }

    private fun stop() {
        val current = snapshot.value ?: return
        try {
            coding.abortCodingSession(current.sessionId)
        } catch (error: Exception) {
            AppLog.error("desktop_host", "agent.dock.stop.failed", error)
        }
    }

    private fun recover(stepId: String, cancel: Boolean) {
        val recovery = snapshot.value?.recoveries?.get(stepId) ?: return
        try {
            if (cancel) coding.cancelRecovery(recovery) else coding.recover(recovery)
        } catch (error: Exception) {
            AppLog.error("desktop_host", "agent.dock.recovery.failed", error)
        }
    }

    private fun restoreOwner() {
        try {
            (owner as? Frame)?.let { frame ->
                frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
            }
            owner.isVisible = true
            owner.toFront()
            owner.requestFocus()
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true)
            }
        } catch (error: Exception) {
            AppLog.error("desktop_host", "agent.dock.restore.failed", error,
                mapOf("result" to "dock_stays_visible"))
        }
    }

    // endregion

    // region geometry

    /**
     * Size the window from the same display scale Compose uses for its density. Window bounds
     * are device pixels while the dock is laid out in dp: guessing here clips the composer at
     * 150 % scaling. Rounding up never clips; it can only leave a transparent pixel.
     */
    private fun applyGeometry(window: ComposeWindow, animate: Boolean) {
        val usable = usableArea(window) ?: return
        val scale = configuration()?.defaultTransform?.scaleX ?: 1.0
        val open = expanded.value
        val widthDp = if (open) expandedWidth.value else PaperAgentDockCollapsedWidth
        val heightDp = if (open) expandedHeight.value else PaperAgentDockCollapsedHeight
        val width = ceil(widthDp.value * scale).toInt().coerceIn(1, usable.width)
        val height = ceil(heightDp.value * scale).toInt().coerceIn(1, usable.height)
        val x = if (edge.value == DockEdge.START) usable.x else usable.x + usable.width - width
        val lowest = (usable.y + usable.height - height).coerceAtLeast(usable.y)
        val y = (usable.y + ((usable.height - height) * offset).roundToInt()).coerceIn(usable.y, lowest)
        applyBounds(window, Rectangle(x, y, width, height), animate)
    }

    /**
     * Grow the window towards its next rectangle instead of teleporting: the compact tab and the
     * expanded card read as one surface morphing, the way a live activity expands in place.
     * Dragging and resizing stay immediate — a handle under the cursor must never lag it.
     */
    private fun applyBounds(window: ComposeWindow, target: Rectangle, animate: Boolean) {
        boundsAnimation?.stop()
        boundsAnimation = null
        val from = window.bounds
        if (!animate || from == target) {
            window.bounds = target
            return
        }
        val startedAt = System.nanoTime()
        boundsAnimation = javax.swing.Timer(BOUNDS_STEP_MILLIS) { _ ->
            val t = ((System.nanoTime() - startedAt) / BOUNDS_DURATION_MILLIS.toDouble() / 1_000_000.0)
                .coerceIn(0.0, 1.0)
            window.bounds = interpolateRect(from, target, easeOut(t))
            if (t >= 1.0) {
                boundsAnimation?.stop()
                boundsAnimation = null
            }
        }.apply { start() }
    }

    /**
     * The owner's screen, minus its taskbar/Dock insets: the panel lives where the app lives.
     * A not-yet-displayable overlay reports no configuration of its own, so the owner's is the
     * single source for both the usable area and the display scale.
     */
    private fun configuration() = owner.graphicsConfiguration
        ?: overlay?.graphicsConfiguration
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment()?.defaultScreenDevice?.defaultConfiguration

    private fun usableArea(window: Window): Rectangle? {
        val configuration = configuration() ?: return null
        val bounds = configuration.bounds ?: return null
        val insets = try { window.toolkit.getScreenInsets(configuration) } catch (error: Exception) {
            AppLog.error("desktop_host", "agent.dock.insets.failed", error, mapOf("result" to "full_screen"))
            null
        }
        val left = insets?.left ?: 0
        val right = insets?.right ?: 0
        val top = insets?.top ?: 0
        val bottom = insets?.bottom ?: 0
        return Rectangle(
            bounds.x + left,
            bounds.y + top,
            (bounds.width - left - right).coerceAtLeast(1),
            (bounds.height - top - bottom).coerceAtLeast(1),
        )
    }

    /**
     * Move along the edge by keeping the grabbed point under the pointer.
     *
     * The position is recomputed from the window's live origin on every event: while dragging,
     * the window travels under the cursor, so deltas measured against the moving origin would
     * feed back into the next event and make the panel jerk. Crossing the screen's middle by a
     * decisive margin re-anchors the panel to that edge; the hysteresis keeps a wiggle around
     * the middle from teleporting it.
     */
    private fun dragStart(x: Float, y: Float) {
        grab = Point(x.roundToInt(), y.roundToInt())
    }

    private fun dragBy(x: Float, y: Float) {
        val window = overlay ?: return
        val held = grab ?: return
        val usable = usableArea(window) ?: return
        val origin = window.locationOnScreen
        val pointerX = origin.x + x.roundToInt()
        val pointerY = origin.y + y.roundToInt()
        val height = window.bounds.height
        val lowest = (usable.y + usable.height - height).coerceAtLeast(usable.y)
        val target = (pointerY - held.y).coerceIn(usable.y, lowest)
        val travel = usable.height - height
        offset = if (travel > 0) (target - usable.y).toFloat() / travel else DEFAULT_OFFSET
        val middle = usable.x + usable.width / 2
        edge.value = when (edge.value) {
            DockEdge.START -> if (pointerX > middle + EDGE_HYSTERESIS) DockEdge.END else DockEdge.START
            DockEdge.END -> if (pointerX < middle - EDGE_HYSTERESIS) DockEdge.START else DockEdge.END
        }
        window.setLocation(
            if (edge.value == DockEdge.START) usable.x else usable.x + usable.width - window.bounds.width,
            target,
        )
    }

    private fun dragEnd() {
        grab = null
        persistPlacement()
    }

    /**
     * Grow or shrink the expanded card. The grip reports device pixels; the card is sized in dp,
     * so the delta crosses the same display scale the window geometry uses. Docked to the start
     * edge the free side is the right one, so a rightward drag widens; docked to the end edge it
     * is the mirror image.
     */
    private fun resizeWidthBy(dxPx: Float) = resizeBy(dxPx, 0f)

    private fun resizeHeightBy(dyPx: Float) = resizeBy(0f, dyPx)

    private fun resizeBy(dxPx: Float, dyPx: Float) {
        val window = overlay ?: return
        val usable = usableArea(window) ?: return
        val scale = configuration()?.defaultTransform?.scaleX ?: 1.0
        val maxWidth = (usable.width / scale).dp - PaperAgentDockShadowMargin * 2
        val maxHeight = (usable.height / scale).dp - PaperAgentDockShadowMargin * 2
        val sign = if (edge.value == DockEdge.START) 1f else -1f
        expandedWidth.value = (expandedWidth.value + (dxPx * sign / scale).dp)
            .coerceIn(PaperAgentDockMinSize, maxWidth)
        expandedHeight.value = (expandedHeight.value + (dyPx / scale).dp)
            .coerceIn(PaperAgentDockMinSize, maxHeight)
        applyGeometry(window, animate = false)
    }

    // endregion

    // region remembered placement

    private fun persistPlacement() {
        val saved = "${edge.value.name}:$offset:${expandedWidth.value.value}:${expandedHeight.value.value}"
        try {
            placement.write(PLACEMENT_KEY, saved)
        } catch (error: Exception) {
            // The panel keeps working; only the next launch loses the place.
            AppLog.error("desktop_host", "agent.dock.placement.save.failed", error)
        }
    }

    private fun restorePlacement() {
        val saved = try { placement.read(PLACEMENT_KEY) } catch (error: Exception) {
            AppLog.error("desktop_host", "agent.dock.placement.load.failed", error,
                mapOf("result" to "default_placement"))
            null
        } ?: return
        val parts = saved.split(':')
        val restoredEdge = DockEdge.values().firstOrNull { it.name == parts[0] } ?: return
        val restoredOffset = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: return
        edge.value = restoredEdge
        offset = restoredOffset
        parts.getOrNull(2)?.toFloatOrNull()?.let { expandedWidth.value = it.dp.coerceAtLeast(PaperAgentDockMinSize) }
        parts.getOrNull(3)?.toFloatOrNull()?.let { expandedHeight.value = it.dp.coerceAtLeast(PaperAgentDockMinSize) }
    }

    // endregion

    // region projection

    /**
     * What the dock shows: every live session for the rail, the selected one's chat, and one
     * aggregate tone for the tab. A quiet workspace keeps the screen edge to itself.
     */
    private fun CodingState.dockSnapshot(settings: AppSettings, selectedId: String?): Snapshot? {
        // `coding` here is this CodingState's own projection, not the injected service.
        val ui = this.coding
        val live = ui.sessions.filter { !it.session.archived }
        if (live.none { it.status != CodingSessionStatus.IDLE }) return null
        val tone = aggregateDockTone(live.map { it.status.activityTone })
        val selected = selectedSession(live, selectedId, ui.currentSessionId)
        val status = selected.status
        val waiting = status == CodingSessionStatus.WAITING
        val now = System.currentTimeMillis()
        val recoveries = mutableMapOf<String, CodingRecovery>()
        return Snapshot(
            model = PaperAgentDockModel(
                sessions = live.sortedWith(
                    compareBy<CodingSessionUi> { RAIL_ORDER.indexOf(it.status) }
                        .thenByDescending { it.session.statusChangedAt },
                ).map { session ->
                    PaperDockSession(
                        id = session.session.id,
                        // The sidebar's own title: a started session is listed by its short
                        // request, never by the placeholder name.
                        name = session.session.sidebarTitle(),
                        tone = session.status.activityTone,
                        running = session.running || session.draft.active,
                        selected = session.session.id == selected.session.id,
                        activityLabel = session.activityLabel(),
                        ageLabel = ageLabel(session.session.statusChangedAt, now),
                        needsYou = session.status in NEEDS_YOU,
                    )
                },
                statusLabel = status.label,
                messages = selected.dockMessages(settings.hideSystemSteps, ui.pendingRecoveries, recoveries),
                liveDetail = selected.liveDetail(),
                pendingQuestion = selected.pendingQuestion(),
                attentionCount = live.count { it.status in NEEDS_YOU || it.status == CodingSessionStatus.UNREAD },
                busy = selected.running || selected.draft.active,
                // A questionnaire is answered in the window that renders it; an input here
                // would look like an answer and be discarded instead.
                canSend = !waiting,
                inputPlaceholder = if (waiting) "Ответьте на вопрос в окне MagicPaper" else "Сообщение агенту…",
                transcriptKey = selected.session.id,
            ),
            tone = tone,
            toneLabel = toneLabel(tone),
            anyRunning = live.any { it.running || it.draft.active },
            sessionId = selected.session.id,
            animate = settings.paperAnimationEnabled,
            recoveries = recoveries.toMap(),
        )
    }

    /** The dock's own selection wins; otherwise the window's session, otherwise the most urgent. */
    private fun selectedSession(
        live: List<CodingSessionUi>,
        selectedId: String?,
        currentSessionId: String?,
    ): CodingSessionUi {
        selectedId?.let { id -> live.firstOrNull { it.session.id == id }?.let { return it } }
        currentSessionId?.let { id -> live.firstOrNull { it.session.id == id }?.let { return it } }
        return live.filter { it.status != CodingSessionStatus.IDLE }
            .minByOrNull { URGENCY.indexOf(it.status) }
            ?: live.first()
    }

    private fun toneLabel(tone: PaperActivityTone): String = when (tone) {
        PaperActivityTone.WORKING -> "работает"
        PaperActivityTone.ATTENTION -> "ждёт ответа"
        PaperActivityTone.UNREAD -> "непрочитанное"
        PaperActivityTone.NEEDS_TESTING -> "нужна проверка"
        PaperActivityTone.QUEUED -> "в очереди"
        PaperActivityTone.READY -> "свободен"
    }

    /** The row's key value: the question it waits on, else what it is doing right now. */
    private fun CodingSessionUi.activityLabel(): String? {
        pendingQuestion()?.takeIf { it.isNotBlank() }?.let { return it }
        return liveDetail()
    }

    private fun CodingSessionUi.pendingQuestion(): String? =
        interactions.firstOrNull()?.questions?.firstOrNull()?.title?.takeIf { it.isNotBlank() }

    /** Age of the current state: a run's duration while working, a frozen span once done. */
    private fun ageLabel(sinceMillis: Long, now: Long): String {
        val seconds = ((now - sinceMillis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds с"
            seconds < 3600 -> "${seconds / 60} мин"
            else -> "${seconds / 3600} ч"
        }
    }

    /** [recoveries] collects the failure action behind each step id the dock will report back. */
    private fun CodingSessionUi.dockMessages(hideSystemSteps: Boolean, pending: Set<CodingRecovery>,
        recoveries: MutableMap<String, CodingRecovery>): List<PaperDockMessage> = messages
        .takeLast(MAX_MESSAGES)
        .map { message ->
            PaperDockMessage(
                id = message.id,
                author = if (message.role == CodingRole.USER) PaperDockAuthor.USER else PaperDockAuthor.AGENT,
                text = message.text.trim().take(MAX_MESSAGE_CHARS),
                systemNotice = message.systemNotice,
                systemContext = message.systemContext,
                failed = message.failed,
                steps = message.steps.filter { it.isVisibleInChat(hideSystemSteps) }.mapIndexed { index, step ->
                    val id = step.id.ifBlank { "${message.id}:legacy:$index" }
                    step.recovery?.let { recoveries[id] = it }
                    PaperDockStep(
                        id = id,
                        kind = when (step.kind) {
                            CodingStepKind.ANSWER -> PaperDockStepKind.ANSWER
                            CodingStepKind.THINKING -> PaperDockStepKind.THINKING
                            CodingStepKind.ERROR -> PaperDockStepKind.ERROR
                            CodingStepKind.TOOL, CodingStepKind.EXEC -> PaperDockStepKind.TOOL
                            CodingStepKind.INFO, CodingStepKind.SYSTEM, CodingStepKind.SUMMARY -> PaperDockStepKind.INFO
                        },
                        title = step.title.take(MAX_MESSAGE_CHARS),
                        tool = step.tool,
                        running = step.running,
                        ok = step.ok,
                        recovery = step.recovery?.let { PaperDockRecovery(it.actionLabel, it.pendingLabel, it in pending) },
                    )
                },
                needsVerification = completedResponseId == message.id && !manuallyVerified,
            )
        }
        .filter { it.systemContext || it.systemNotice || it.text.isNotEmpty() || it.steps.isNotEmpty() }

    /**
     * What the run is doing right now. The saved transcript only gains a message when a step
     * finishes, so a dock built from it alone would look frozen during a long run.
     */
    private fun CodingSessionUi.liveDetail(): String? {
        val draft = this.draft
        if (!running && !draft.active) return null
        if (draft.awaitingApproval) return "Ждёт подтверждения действия"
        if (draft.awaitingModel) return "Ожидает ответа модели"
        draft.steps.lastOrNull { it.running }?.title?.takeIf { it.isNotBlank() }?.let { return it }
        draft.reasoningSummary.takeIf { it.isNotBlank() }?.let { return it }
        draft.steps.lastOrNull()?.title?.takeIf { it.isNotBlank() }?.let { return it }
        draft.thinking.lineSequence().lastOrNull { it.isNotBlank() }?.let { return it.trim().take(160) }
        return "Прогон выполняется"
    }

    // endregion
}

/** Linear blend of two rectangles; [t] is already eased by the caller. */
internal fun interpolateRect(from: Rectangle, to: Rectangle, t: Double): Rectangle {
    val k = t.coerceIn(0.0, 1.0)
    fun lerp(a: Int, b: Int) = (a + (b - a) * k).roundToInt()
    return Rectangle(lerp(from.x, to.x), lerp(from.y, to.y),
        lerp(from.width, to.width), lerp(from.height, to.height))
}

/** Cubic ease-out: the morph decelerates into its target instead of stopping dead. */
internal fun easeOut(t: Double): Double {
    val inv = 1 - t.coerceIn(0.0, 1.0)
    return 1 - inv * inv * inv
}
