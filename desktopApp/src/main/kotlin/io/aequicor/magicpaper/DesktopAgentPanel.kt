package io.aequicor.magicpaper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.designsystem.PaperAgentDock
import io.aequicor.magicpaper.designsystem.PaperAgentDockCollapsedHeight
import io.aequicor.magicpaper.designsystem.PaperAgentDockCollapsedWidth
import io.aequicor.magicpaper.designsystem.PaperAgentDockExpandedHeight
import io.aequicor.magicpaper.designsystem.PaperAgentDockExpandedWidth
import io.aequicor.magicpaper.designsystem.PaperAgentDockModel
import io.aequicor.magicpaper.designsystem.PaperDockAuthor
import io.aequicor.magicpaper.designsystem.PaperDockMessage
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.ui.CodingService
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingState
import io.aequicor.magicpaper.ui.screens.ActivityDot
import io.aequicor.magicpaper.ui.screens.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Desktop
import java.awt.Frame
import java.awt.GraphicsEnvironment
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
    private val placement: KeyValueStore,
) : AutoCloseable {
    private companion object {
        const val PLACEMENT_KEY = "desktop.agentDock.placement"
        const val DEFAULT_OFFSET = 0.34f
        /** The dock shows a recent window of the transcript, not the whole journal. */
        const val MAX_MESSAGES = 40
        const val MAX_MESSAGE_CHARS = 2000
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
        val status: CodingSessionStatus,
        val sessionId: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val snapshot = mutableStateOf<Snapshot?>(null)
    private val expanded = mutableStateOf(false)
    private val input = mutableStateOf("")
    private val edge = mutableStateOf(DockEdge.START)

    private var overlay: ComposeWindow? = null
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
            coding.state.collect { state ->
                snapshot.value = state.dockSnapshot()
                // Window visibility is an AWT decision and belongs on the EDT.
                SwingUtilities.invokeLater(::applyVisibility)
            }
        }
        applyVisibility()
    }

    override fun close() {
        scope.cancel()
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
            applyGeometry(window)
            if (!window.isVisible) {
                window.isVisible = true
                AppLog.info("desktop_host", "agent.dock.shown", mapOf(
                    "status" to snapshot.value?.status?.name.orEmpty(),
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
                dockedToStart = edge.value == DockEdge.START,
                // The sidebar's own dot and label: one session never reads as two states.
                indicator = { ActivityDot(current.status, size = 14) },
                input = input.value,
                onInputChange = { input.value = it },
                onSend = ::send,
                onStop = ::stop,
                onOpenMainWindow = ::restoreOwner,
                onDragBy = ::dragBy,
                onDragEnd = ::persistPlacement,
            )
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded.value == value) return
        expanded.value = value
        overlay?.let(::applyGeometry)
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
    private fun applyGeometry(window: ComposeWindow) {
        val usable = usableArea(window) ?: return
        val scale = configuration()?.defaultTransform?.scaleX ?: 1.0
        val open = expanded.value
        val width = ceil((if (open) PaperAgentDockExpandedWidth else PaperAgentDockCollapsedWidth).value * scale)
            .toInt().coerceIn(1, usable.width)
        val height = ceil((if (open) PaperAgentDockExpandedHeight else PaperAgentDockCollapsedHeight).value * scale)
            .toInt().coerceIn(1, usable.height)
        val x = if (edge.value == DockEdge.START) usable.x else usable.x + usable.width - width
        val lowest = (usable.y + usable.height - height).coerceAtLeast(usable.y)
        val y = (usable.y + ((usable.height - height) * offset).roundToInt()).coerceIn(usable.y, lowest)
        window.setBounds(x, y, width, height)
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
     * Move along the edge. Crossing the screen's middle re-anchors the panel to that edge, so
     * the reader can park it left or right without a separate setting.
     */
    private fun dragBy(deltaX: Float, deltaY: Float) {
        val window = overlay ?: return
        val usable = usableArea(window) ?: return
        val bounds = window.bounds
        val lowest = (usable.y + usable.height - bounds.height).coerceAtLeast(usable.y)
        val proposed = (bounds.y + deltaY.roundToInt()).coerceIn(usable.y, lowest)
        val travel = usable.height - bounds.height
        offset = if (travel > 0) (proposed - usable.y).toFloat() / travel else DEFAULT_OFFSET
        val centre = bounds.x + bounds.width / 2 + deltaX.roundToInt()
        edge.value = if (centre < usable.x + usable.width / 2) DockEdge.START else DockEdge.END
        window.setLocation(
            if (edge.value == DockEdge.START) usable.x else usable.x + usable.width - bounds.width,
            proposed,
        )
    }

    // endregion

    // region remembered placement

    private fun persistPlacement() {
        val saved = "${edge.value.name}:$offset"
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
        if (parts.size != 2) return
        val restoredEdge = DockEdge.values().firstOrNull { it.name == parts[0] } ?: return
        val restoredOffset = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: return
        edge.value = restoredEdge
        offset = restoredOffset
    }

    // endregion

    // region projection

    /**
     * The session the dock speaks for. The selected one wins while it is not idle; otherwise the
     * most urgent running session does, so background work stays visible when the reader left an
     * idle session on screen.
     */
    private fun CodingState.dockSnapshot(): Snapshot? {
        val session = dockSession() ?: return null
        val status = session.status
        val waiting = status == CodingSessionStatus.WAITING
        return Snapshot(
            model = PaperAgentDockModel(
                statusLabel = status.label,
                sessionLabel = session.session.name,
                messages = session.dockMessages(),
                liveDetail = session.liveDetail(),
                busy = session.running || session.draft.active,
                // A questionnaire is answered in the window that renders it; an input here
                // would look like an answer and be discarded instead.
                canSend = !waiting && !session.session.archived,
                inputPlaceholder = when {
                    waiting -> "Ответьте на вопрос в окне MagicPaper"
                    session.session.archived -> "Сессия в архиве"
                    else -> "Сообщение агенту…"
                },
                transcriptKey = session.session.id,
            ),
            status = status,
            sessionId = session.session.id,
        )
    }

    private fun CodingState.dockSession(): CodingSessionUi? {
        // `coding` here is this CodingState's own projection, not the injected service.
        val ui = this.coding
        val live = ui.sessions.filter { !it.session.archived && it.status != CodingSessionStatus.IDLE }
        ui.currentSession?.takeIf { !it.session.archived && it.status != CodingSessionStatus.IDLE }?.let { return it }
        return live.minByOrNull { URGENCY.indexOf(it.status) }
    }

    private fun CodingSessionUi.dockMessages(): List<PaperDockMessage> = messages
        .filterNot { it.systemContext }
        .takeLast(MAX_MESSAGES)
        .mapNotNull { message ->
            val text = message.text.trim()
            if (text.isEmpty()) null else PaperDockMessage(
                id = message.id,
                author = if (message.role == CodingRole.USER) PaperDockAuthor.USER else PaperDockAuthor.AGENT,
                text = text.take(MAX_MESSAGE_CHARS),
            )
        }

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
