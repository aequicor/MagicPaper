package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.screens.CodingChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class MessageHistoryActionsRenderTest {
    @Test fun transcriptPreviewsShowMessageActionsAtNormalAndNarrowWidths() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val still = object : MotionDurationScale { override val scaleFactor = 0f }
            for ((width, scale) in listOf(430 to 1f, 1000 to 1f, 720 to 2f)) ImageComposeScene(width, 900, coroutineContext = Dispatchers.Unconfined + still) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    io.aequicor.magicpaper.ui.screens.ChatTranscriptPreview()
                }
            }.use { scene ->
                repeat(12) { scene.render(it * 32_000_000L).close(); runCurrent() }
                scene.render(500_000_000L).use { image ->
                    val directory = File("build/reports/message-history").apply { mkdirs() }
                    image.encodeToData()!!.use { File(directory, "transcript-$width.png").writeBytes(it.bytes) }
                }
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                val actions = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    .filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Действия с сообщением") == true }
                assertTrue(actions.isNotEmpty())
                assertTrue(actions.all { it.boundsInRoot.right <= width && it.boundsInRoot.width > 0 })
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun codingContextMenuForksTheSelectedMessageAndHasNoSessionForkButton() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val forked = mutableListOf<String?>()
            val messages = listOf(
                CodingMessage("request", CodingRole.USER, "Составь план проверки", createdAt = 1),
                CodingMessage("answer", CodingRole.AGENT, "Проверим сообщения и меню.", createdAt = 2,
                    planning = PlanningChatBlock("plan")),
                CodingMessage("card", CodingRole.AGENT, "План проверки", createdAt = 3,
                    planning = PlanningChatBlock("plan", graph = true)),
            )
            ImageComposeScene(430, 700, coroutineContext = Dispatchers.Unconfined) {
                PaperTheme {
                    CompositionLocalProvider(LocalChatPresentation provides DefaultChatPresentation) {
                    Box(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas)) {
                        CodingChat(CodingProject("p", "Проект", "/fixture", 0),
                            CodingSessionUi(CodingSession("s", "p", "Проверка чата", 0), messages = messages),
                            false, true, { _, _ -> }, {}, { _, _ -> },
                            onForkSession = { forked += it; Result.success("fork") })
                    }
                    }
                }
            }.use { scene ->
                var frame = 0L
                fun render() { repeat(12) { scene.render(++frame * 32_000_000L).close(); runCurrent() } }
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                fun text(label: String) = nodes().first { n ->
                    n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                }
                render()
                assertTrue(nodes().none { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Форк сессии" } == true })
                for (message in messages) {
                    val point = text(message.text).boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse,
                        buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                    scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse,
                        buttons = PointerButtons(), button = PointerButton.Secondary)
                    render()
                    text("Форк до этого сообщения").config[SemanticsActions.OnClick].action!!.invoke()
                    render()
                }
                assertEquals(listOf<String?>("request", "answer", "card"), forked)
                scene.render(++frame * 32_000_000L).use { image ->
                    val directory = File("build/reports/message-history").apply { mkdirs() }
                    image.encodeToData()!!.use { File(directory, "coding-430.png").writeBytes(it.bytes) }
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun completeCopyEditorAndDeleteUseRealControlsAtNarrowAndLargeTextSizes() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (scale in listOf(1f, 1.5f)) {
                var clipboardText: AnnotatedString? = null
                val clipboard = object : ClipboardManager {
                    override fun setText(annotatedString: AnnotatedString) { clipboardText = annotatedString }
                    override fun getText() = clipboardText
                }
                val complete = "Полный текст\n".repeat(2000)
                var edited: String? = null
                var deleted = false
                ImageComposeScene(if (scale == 1f) 430 else 760, 900, coroutineContext = Dispatchers.Unconfined) {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalDensity provides Density(1f, scale)) {
                        PaperTheme {
                            Column(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas).padding(12.dp)) {
                                PaperText("Сообщение пользователя")
                                MessageHistoryActions("message", "Исходный запрос", { complete }, true,
                                    onEdit = { edited = it; Result.success(Unit) },
                                    onDelete = { deleted = true; Result.success(Unit) }, onFork = { Result.success("fork") })
                            }
                        }
                    }
                }.use { scene ->
                    var frame = 0L
                    fun render(name: String? = null) {
                        repeat(5) { scene.render(++frame * 32_000_000L).close(); runCurrent() }
                        if (name != null) scene.render(++frame * 32_000_000L).use { image ->
                            val directory = File("build/reports/message-history").apply { mkdirs() }
                            image.encodeToData()!!.use { File(directory, "$name-$scale.png").writeBytes(it.bytes) }
                        }
                    }
                    fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                    fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                    fun click(label: String) {
                        val node = nodes().first { node ->
                            (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) &&
                                node.config.getOrNull(SemanticsActions.OnClick) != null
                        }
                        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                        render()
                    }
                    render("actions")
                    click("Действия с сообщением")
                    click("Копировать целиком")
                    assertEquals(complete, clipboardText?.text)
                    click("Действия с сообщением"); render("menu")
                    click("Редактировать"); render("editor")
                    val field = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
                    field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Исправленный запрос")); render()
                    click("Сохранить и отправить")
                    assertEquals("Исправленный запрос", edited)
                    click("Действия с сообщением"); click("Удалить из истории и контекста"); render("delete")
                    assertFalse(deleted)
                    click("Удалить")
                    assertTrue(deleted)
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun agentAndBusyMessageMenusDoNotOfferEditingOrEnableDestructiveChanges() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            ImageComposeScene(430, 500, coroutineContext = Dispatchers.Unconfined) {
                PaperTheme { PaperMessageActions({}, onFork = {}, onDelete = {}, historyEnabled = false) }
            }.use { scene ->
                fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
                fun nodes() = scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                repeat(5) { scene.render(it * 32_000_000L).close() }
                nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Действия с сообщением") == true }
                    .config[SemanticsActions.OnClick].action!!.invoke()
                repeat(5) { scene.render((it + 6) * 32_000_000L).close() }
                assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Редактировать" } == true })
                val deletion = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Удалить из истории и контекста" } == true }
                assertTrue(deletion.config.contains(SemanticsProperties.Disabled))
            }
        } finally { Dispatchers.resetMain() }
    }
}
