package io.aequicor.magicpaper

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.ComposeWindow
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.ui.CodingState
import kotlinx.coroutines.flow.StateFlow
import java.awt.Color as AwtColor
import java.nio.file.Path

/**
 * Always-on-top overlay that shows agent activity when the main window is minimized.
 * Collapsed: a thin strip with a pulsing status dot.
 * Expanded on hover: a chat panel with recent messages and an input field.
 * Position is persisted along the screen edge.
 */
internal class DesktopAgentPanel(
    private val codingServiceState: StateFlow<CodingState>,
    private val onSend: (String) -> Unit,
    private val onRestore: () -> Unit,
) : AutoCloseable {
    private var overlay: ComposeWindow? = null
    private val positionFile: Path = agentPanelPositionFile()

    fun show() {
        val window = overlay ?: createWindow().also { overlay = it }
        window.isVisible = true
    }

    fun hide() {
        overlay?.isVisible = false
    }

    override fun close() {
        overlay?.dispose()
        overlay = null
    }

    private fun createWindow(): ComposeWindow {
        val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        val savedPosition = loadPosition()
        val panelHeight = 600
        val initialY = savedPosition ?: (screen.height - panelHeight) / 2

        return ComposeWindow().apply {
            name = "MagicPaperAgentPanel"
            title = "MagicPaper · агент"
            isUndecorated = true
            isTransparent = true
            background = AwtColor(0, 0, 0, 0)
            isAlwaysOnTop = true
            isAutoRequestFocus = false
            focusableWindowState = true

            val collapsedWidth = 48
            setBounds(screen.x, initialY, collapsedWidth, panelHeight)

            setContent {
                PaperTheme {
                    AgentPanelContent(
                        codingState = codingServiceState,
                        collapsedWidth = collapsedWidth.dp,
                        expandedWidth = 380.dp,
                        panelHeight = panelHeight.dp,
                        onSend = onSend,
                        onRestore = onRestore,
                    )
                }
            }
            isVisible = true
        }
    }

    private fun loadPosition(): Int? = try {
        val file = positionFile.toFile()
        if (file.exists()) file.readText().trim().toIntOrNull() else null
    } catch (_: Exception) { null }

    private fun savePosition(y: Int) {
        try {
            val file = positionFile.toFile()
            file.parentFile?.mkdirs()
            file.writeText(y.toString())
        } catch (_: Exception) { /* ignore persistence failures */ }
    }
}

private fun agentPanelPositionFile(): Path {
    val base = System.getProperty("user.home", ".")
    return Path.of(base, ".MagicPaper", "agent-panel-position.txt")
}

@Composable
private fun AgentPanelContent(
    codingState: StateFlow<CodingState>,
    collapsedWidth: Dp,
    expandedWidth: Dp,
    panelHeight: Dp,
    onSend: (String) -> Unit,
    onRestore: () -> Unit,
) {
    val state by codingState.collectAsState()
    val currentSession = state.coding.currentSession
    val status = currentSession?.status ?: CodingSessionStatus.IDLE
    val running = currentSession?.running == true
    val messages = currentSession?.messages.orEmpty()
    val sessionName = currentSession?.session?.name?.takeIf { it.isNotBlank() }
        ?: currentSession?.session?.id?.take(8).orEmpty()

    var expanded by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    val dotColor = statusDotColor(status)
    val currentWidth = if (expanded) expandedWidth else collapsedWidth

    Box(
        modifier = Modifier
            .width(currentWidth)
            .height(panelHeight)
            .pointerInput(expanded) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> if (!expanded) expanded = true
                            PointerEventType.Exit -> {
                                val pos = event.changes.firstOrNull()?.position
                                if (pos != null && (pos.x < 0 || pos.x > size.width || pos.y < 0 || pos.y > size.height)) {
                                    expanded = false
                                }
                            }
                        }
                    }
                }
            }
    ) {
        PaperSurface(
            modifier = Modifier.fillMaxSize(),
            kind = PaperSurfaceKind.RAISED,
            shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 12.dp, bottomEnd = 12.dp),
        ) {
            if (expanded) {
                ExpandedPanelContent(
                    messages = messages,
                    sessionName = sessionName,
                    status = status,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            onSend(inputText.trim())
                            inputText = ""
                        }
                    },
                    onRestore = onRestore,
                    dotColor = dotColor,
                )
            } else {
                CollapsedPanelContent(
                    dotColor = dotColor,
                    running = running,
                    sessionName = sessionName,
                )
            }
        }
    }
}

@Composable
private fun CollapsedPanelContent(
    dotColor: Color,
    running: Boolean,
    sessionName: String,
) {
    val colors = LocalPaperColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        PulsingDot(color = dotColor, active = running)

        if (sessionName.isNotEmpty()) {
            PaperText(
                sessionName,
                style = LocalPaperTypography.current.chrome,
                color = colors.secondaryText,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        PaperText(
            "↗",
            style = LocalPaperTypography.current.chrome,
            color = colors.secondaryText,
        )
    }
}

@Composable
private fun ExpandedPanelContent(
    messages: List<CodingMessage>,
    sessionName: String,
    status: CodingSessionStatus,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRestore: () -> Unit,
    dotColor: Color,
) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    val scrollState = androidx.compose.foundation.rememberScrollState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.sm).padding(bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color = dotColor, active = status == CodingSessionStatus.WORKING, size = 10.dp)
                Spacer(Modifier.width(spacing.xs))
                PaperText(
                    statusLabel(status),
                    style = LocalPaperTypography.current.chrome,
                    color = colors.secondaryText,
                )
            }
            PaperText(
                "↗ Развернуть",
                style = LocalPaperTypography.current.chrome,
                color = colors.action,
                modifier = Modifier.paperClickable(onClick = onRestore),
            )
        }

        // Session name
        if (sessionName.isNotEmpty()) {
            PaperText(
                sessionName,
                style = LocalPaperTypography.current.label,
                color = colors.text,
                modifier = Modifier.padding(horizontal = spacing.sm).padding(bottom = spacing.xs),
            )
        }

        // Messages area
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = spacing.sm)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.canvas.copy(alpha = 0.5f))
                .padding(spacing.xs)
                .verticalScroll(scrollState),
        ) {
            Column {
                val visibleMessages = messages.takeLast(50).filter { !it.systemContext }
                if (visibleMessages.isEmpty()) {
                    PaperText(
                        "Нет сообщений",
                        style = LocalPaperTypography.current.body,
                        color = colors.disabled,
                        modifier = Modifier.padding(spacing.sm),
                    )
                } else {
                    visibleMessages.forEach { message ->
                        MessageBubble(message, colors)
                        Spacer(Modifier.height(spacing.xxs))
                    }
                }
            }
        }

        // Input area
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperInput(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { PaperText("Сообщение агенту…", color = colors.disabled) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(spacing.xs))
            PaperIconButton(label = "Отправить", onClick = onSend, modifier = Modifier.size(40.dp)) {
                PaperText("→", role = PaperTextRole.CHROME)
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: CodingMessage,
    colors: PaperColors,
) {
    val isUser = message.role == CodingRole.USER
    val bgColor = if (isUser) colors.userMessageSurface else colors.agentMessageSurface

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bgColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            PaperText(
                message.text.take(500),
                style = LocalPaperTypography.current.body,
                color = colors.text,
            )
        }
    }
}

@Composable
private fun PulsingDot(color: Color, active: Boolean, size: Dp = 12.dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(
        modifier = Modifier.size(size).scale(if (active) scale else 1f).clip(CircleShape).background(color),
    )
}

private fun statusDotColor(status: CodingSessionStatus): Color {
    val colors = PaperColors()
    return when (status) {
        CodingSessionStatus.WORKING -> colors.activityRed
        CodingSessionStatus.WAITING -> colors.activityYellow
        CodingSessionStatus.CONFIRMATION -> colors.activityPurple
        CodingSessionStatus.BLOCKED -> colors.error
        CodingSessionStatus.QUEUED -> colors.disabled
        CodingSessionStatus.SCHEDULED -> colors.activityBlue
        CodingSessionStatus.UNREAD -> colors.activityPurple
        CodingSessionStatus.NEEDS_TESTING -> colors.activityYellow
        CodingSessionStatus.IDLE -> colors.activityGreen
    }
}

private fun statusLabel(status: CodingSessionStatus): String = when (status) {
    CodingSessionStatus.WORKING -> "Работает…"
    CodingSessionStatus.WAITING -> "Ожидает ответа"
    CodingSessionStatus.CONFIRMATION -> "Подтверждение"
    CodingSessionStatus.BLOCKED -> "Ошибка"
    CodingSessionStatus.QUEUED -> "В очереди"
    CodingSessionStatus.SCHEDULED -> "Запланировано"
    CodingSessionStatus.UNREAD -> "Непрочитано"
    CodingSessionStatus.NEEDS_TESTING -> "Тестирование"
    CodingSessionStatus.IDLE -> "Готов"
}
