package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ResearchWorkspaceRenderTest {
    @Test fun unavailableSourceRemovalUsesTheCorrectGroupAndIsDisabledWhileSaving() {
        val state = androidx.compose.runtime.mutableStateOf(researchPreviewState().let { state ->
            state.copy(sourceReadProblems = mapOf("research" to mapOf(
                "url:https://developer.android.com/topic/architecture" to "Ошибка HTTP 500",
                "url:https://developer.android.com/compose" to "Истекло время ожидания страницы")))
        })
        val saving = androidx.compose.runtime.mutableStateOf(false)
        val removed = mutableListOf<Pair<String, io.aequicor.magicpaper.domain.ResearchResourceScope>>()
        var selections = 0
        val scene = onUi { ImageComposeScene(1280, 850) {
            io.aequicor.magicpaper.designsystem.PaperTheme {
                ResearchWorkspaceContent(state.value, saving = saving.value,
                    onRemoveResource = { id, target -> removed += id to target },
                    onResourceEnabled = { _, _ -> selections++ }) {}
            }
        } }
        var frame = 0L
        fun settle() = repeat(12) { onUi { scene.render(++frame * 32_000_000L).close() } }
        fun click(button: SemanticsNode) {
            scene.sendPointerEvent(PointerEventType.Press, button.boundsInRoot.center,
                type = PointerType.Mouse, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, button.boundsInRoot.center,
                type = PointerType.Mouse, button = PointerButton.Primary)
        }
        val sharedLabel = "Убрать источник: Guide to app architecture"
        val questionLabel = "Убрать источник: Jetpack Compose"
        try {
            settle()
            onUi {
                click(scene.action(sharedLabel))
                click(scene.action(questionLabel))
                assertEquals(listOf("architecture" to io.aequicor.magicpaper.domain.ResearchResourceScope.SHARED,
                    "compose" to io.aequicor.magicpaper.domain.ResearchResourceScope.QUESTION), removed)
                assertEquals(0, selections)
                assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .contains("Убрать источник: План обучения.pdf") })
                saving.value = true
            }
            settle()
            onUi {
                val button = scene.action(sharedLabel)
                assertTrue(button.config.contains(SemanticsProperties.Disabled))
                click(button)
                assertEquals(2, removed.size)
                state.value = state.value.copy(sourceReadProblems = emptyMap())
            }
            settle()
            onUi { assertFalse(scene.nodes().any { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty().any { it.startsWith("Убрать источник:") } }, "Permanent delete actions disappear after source recovery") }
        } finally { onUi { scene.close() } }
    }

    @Test fun sourceDomainOpensTheValidatedPageAndRecoversFromBrowserFailure() {
        val opened = mutableListOf<String>()
        var fail = true
        val handler = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                if (fail) error("Test browser unavailable")
                opened += uri
            }
        }
        val scene = onUi { ImageComposeScene(1280, 1000) {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides handler) { ResearchWorkspacePreview() }
        } }
        fun settle() { repeat(16) { onUi { scene.render(it * 32_000_000L).close() } } }
        try {
            settle()
            val checkboxLabel = "Использовать источник: Guide to app architecture"
            val before = onUi { scene.action(checkboxLabel).config[SemanticsProperties.ToggleableState] }
            onUi { scene.action("Открыть сайт: developer.android.com").config[SemanticsActions.OnClick].action!!.invoke() }
            settle()
            onUi {
                assertTrue(scene.text("Не удалось открыть источник. Повторите попытку.").boundsInRoot.height > 0)
                fail = false
                scene.action("Открыть сайт: developer.android.com").config[SemanticsActions.OnClick].action!!.invoke()
            }
            settle()
            onUi {
                assertEquals(listOf("https://developer.android.com/topic/architecture"), opened)
                assertEquals(before, scene.action(checkboxLabel).config[SemanticsProperties.ToggleableState], "Opening the page does not toggle source selection")
                assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Не удалось открыть источник. Повторите попытку." } })
            }
        } finally { onUi { scene.close() } }
    }

    @Test fun answerSourcesAreCollapsedUntilTheirOwnHeaderIsActivated() {
        val scene = onUi { ImageComposeScene(1280, 1000) { ResearchWorkspacePreview() } }
        fun settle() { repeat(16) { onUi { scene.render(it * 32_000_000L).close() } } }
        fun hasCitation() = scene.nodes().any {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Kotlin Documentation" }
        }
        try {
            settle()
            onUi {
                assertFalse(hasCitation())
                val header = scene.action("Развернуть источники ответа")
                assertEquals("Свёрнуто, источников: 2", header.config[SemanticsProperties.StateDescription])
                header.config[SemanticsActions.OnClick].action!!.invoke()
            }
            settle()
            onUi {
                assertTrue(hasCitation())
                scene.action("Свернуть источники ответа").config[SemanticsActions.OnClick].action!!.invoke()
            }
            settle()
            onUi { assertFalse(hasCitation()) }
            scene.capture("answer-sources-collapsed", 900_000_000L)
        } finally { onUi { scene.close() } }
    }

    @Test fun browserReadHasExplicitActionBusyFeedbackAndRecoveryAtNarrowAndLargeTextSizes() {
        val cases = io.aequicor.magicpaper.ui.ResearchBrowserPhase.entries.map { it to (it != io.aequicor.magicpaper.ui.ResearchBrowserPhase.OPENING) } +
            (io.aequicor.magicpaper.ui.ResearchBrowserPhase.FAILED to false)
        for (scale in listOf(1f, 2f)) for ((phase, pageOpen) in cases) {
            var reads = 0
            var closes = 0
            val width = if (scale == 1f) 360 else 560
            val scene = onUi { ImageComposeScene(width, 640) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    ResearchBrowserPreview(phase, { reads++ }, { closes++ }, pageOpen)
                }
            } }
            try {
                repeat(6) { onUi { scene.render(it * 32_000_000L).close() } }
                onUi {
                    val read = scene.action(if (phase == io.aequicor.magicpaper.ui.ResearchBrowserPhase.FAILED && !pageOpen) "Повторить открытие" else "Прочитать страницу")
                    assertTrue(read.boundsInRoot.right <= width)
                    assertTrue(read.boundsInRoot.bottom <= 640)
                    if (phase == io.aequicor.magicpaper.ui.ResearchBrowserPhase.READY || phase == io.aequicor.magicpaper.ui.ResearchBrowserPhase.FAILED) {
                        read.config[SemanticsActions.OnClick].action!!.invoke()
                        assertEquals(1, reads)
                    } else assertTrue(read.config.contains(SemanticsProperties.Disabled))
                    scene.action("Отмена").config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(1, closes)
                }
                scene.capture("browser-${phase.name.lowercase()}${if (!pageOpen) "-closed" else ""}-$scale", 240_000_000L)
            } finally { onUi { scene.close() } }
        }
    }
    @Test fun modelFailureExplainsRecoveryWithoutInventingAPausedAnswerOrDuplicatingTheError() {
        for ((width, scale) in listOf(680 to 1f, 360 to 1f, 720 to 2f)) {
            var resumed = 0
            val scene = onUi { ImageComposeScene(width, 600) {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    ResearchModelFailurePreview { resumed++ }
                }
            } }
            try {
                repeat(8) { onUi { scene.render(it * 32_000_000L).close() } }
                onUi {
                    val text = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                    assertEquals(1, text.count { it == io.aequicor.magicpaper.domain.RESEARCH_MODEL_FAILURE })
                    assertTrue(text.any { "подключение к модели" in it })
                    assertFalse("Ответ агента" in text)
                    assertFalse("Приостановлено" in text)
                    assertTrue(scene.text(io.aequicor.magicpaper.domain.RESEARCH_MODEL_FAILURE).boundsInRoot.right <= width)
                    scene.action("Продолжить").config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(1, resumed)
                }
                scene.capture(if (width == 360) "model-failure-narrow" else "model-failure-$scale", 320_000_000L)
            } finally { onUi { scene.close() } }
        }
    }

    @Test fun groupSelectionIsIndependentOfDisclosureAndTheOtherGroup() {
        val scene = onUi { ImageComposeScene(1280, 850) { ResearchWorkspacePreview() } }
        var frame = 0L
        fun render() { repeat(8) { onUi { scene.render(++frame * 32_000_000L).close() } } }
        fun click(label: String) { onUi { scene.action(label).config[SemanticsActions.OnClick].action!!.invoke() }; render() }
        fun checked(label: String) = scene.action(label).config[SemanticsProperties.ToggleableState]
        try {
            render()
            onUi { assertEquals(androidx.compose.ui.state.ToggleableState.Indeterminate, checked("Выбрать все: Общие для чата")) }
            click("Выбрать все: Общие для чата")
            onUi {
                assertEquals(androidx.compose.ui.state.ToggleableState.On, checked("Использовать источник: Guide to app architecture"))
                assertEquals(androidx.compose.ui.state.ToggleableState.On, checked("Снять выбор со всех: Только этот вопрос"))
            }
            click("Свернуть: Общие для чата")
            click("Снять выбор со всех: Общие для чата")
            onUi {
                assertEquals(androidx.compose.ui.state.ToggleableState.Off, checked("Выбрать все: Общие для чата"))
                assertTrue(scene.action("Развернуть: Общие для чата").boundsInRoot.height > 0)
                assertEquals(androidx.compose.ui.state.ToggleableState.On, checked("Использовать источник: Jetpack Compose"))
            }
            click("Снять выбор со всех: Только этот вопрос")
            click("Выбрать все: Общие для чата")
            click("Развернуть: Общие для чата")
            onUi {
                assertEquals(androidx.compose.ui.state.ToggleableState.On, checked("Использовать источник: План обучения.pdf"))
                assertEquals(androidx.compose.ui.state.ToggleableState.Off, checked("Использовать источник: Jetpack Compose"))
            }
            scene.capture("source-group-selection", ++frame * 32_000_000L)
        } finally { onUi { scene.close() } }
    }

    @Test fun completedReadUsesTimelineDetailWhileTheAgentIsStillPending() {
        val busy = androidx.compose.runtime.mutableStateOf(true)
        val paused = androidx.compose.runtime.mutableStateOf(false)
        var pauses = 0
        val steps = listOf(io.aequicor.magicpaper.domain.CodingStep(io.aequicor.magicpaper.domain.CodingStepKind.TOOL,
            "Прочитано источников: 4; недоступно: 2", tool = "web.read", id = "checked"))
        val scene = onUi { ImageComposeScene(640, 300) {
            io.aequicor.magicpaper.designsystem.PaperTheme {
                io.aequicor.magicpaper.designsystem.PaperSurface {
                    ResearchActivity(steps, busy.value, paused.value,
                        onPause = { pauses++; busy.value = false; paused.value = true },
                        onResume = { busy.value = true; paused.value = false })
                }
            }
        } }
        var frame = 0L
        fun render() { repeat(5) { onUi { scene.render(++frame * 32_000_000L).close() } } }
        fun click(label: String) = onUi { scene.action(label).config[SemanticsActions.OnClick].action!!.invoke() }
        try {
            render()
            onUi {
                val title = scene.text("Проверка выбранных источников")
                val detail = scene.text("Прочитано источников: 4; недоступно: 2")
                assertTrue(detail.boundsInRoot.top > title.boundsInRoot.bottom)
                assertEquals(title.boundsInRoot.left, detail.boundsInRoot.left)
                assertTrue(scene.text("Ожидаю ответ агента").boundsInRoot.height > 0)
                val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                scene.text("Остановить").config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
                assertEquals(11f, layout.single().layoutInput.style.fontSize.value)
            }
            scene.capture("activity-waiting", ++frame * 32_000_000L)
            click("Свернуть ход исследования"); render()
            onUi { assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Проверка выбранных источников" } }) }
            click("Развернуть ход исследования"); render()
            click("Остановить"); render()
            assertEquals(1, pauses)
            onUi { assertTrue(scene.text("Исследование приостановлено").boundsInRoot.height > 0) }
            scene.capture("activity-paused", ++frame * 32_000_000L)
            click("Продолжить"); render()
            assertTrue(busy.value)
            val entries = researchActivityEntries(steps, busy = false, paused = false)
            assertEquals(1, entries.size, "A completed request has no invented pending stages")
            assertEquals("Прочитано источников: 4", entries.single().detail)
            assertEquals("недоступно: 2", entries.single().detailProblem)
            for (title in listOf("Прочитано источников: 4", "Прочитано источников: 4; недоступно: 0")) {
                val readable = researchActivityEntries(listOf(steps.single().copy(title = title)), false, false).single()
                assertEquals(title, readable.detail)
                assertNull(readable.detailProblem, "A successful check must not show an unavailable-source warning")
            }
            val unrelated = researchActivityEntries(listOf(steps.single().copy(tool = "web.search")), false, false).single()
            assertNull(unrelated.detailProblem, "Only source readability results contain these counts")
            val streaming = researchActivityEntries(steps, busy = true, paused = false, answering = true)
            assertEquals("Готовлю ответ", streaming.last().heading, "Streaming text must not be labelled as waiting for the answer")
            val searching = researchActivityEntries(listOf(io.aequicor.magicpaper.domain.CodingStep(
                io.aequicor.magicpaper.domain.CodingStepKind.TOOL, "Ищу источники", tool = "web.search", running = true)), true, false)
            assertEquals(1, searching.size, "A running search must not acquire a simultaneous guessed answer stage")
            assertEquals("Ищу дополнительные источники", searching.single().heading)
        } finally { onUi { scene.close() } }
    }

    @Test fun documentHeadingFollowsTheSelectedQuestion() {
        val scene = onUi { ImageComposeScene(1280, 850) { ResearchWorkspacePreview() } }
        var frame = 0L
        fun render() { repeat(12) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        fun heading() = scene.nodes().first { it.config.contains(SemanticsProperties.Heading) }
            .config[SemanticsProperties.Text].single().text
        try {
            render()
            onUi { assertEquals("С чего начать Android-разработку?", heading()) }
            onUi { scene.action("Вопрос 2: Архитектура приложения").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi { assertEquals("Архитектура приложения", heading()) }
            scene.capture("selected-question-heading", ++frame * 32_000_000L)
            onUi { scene.action("Вопрос 1: С чего начать Android-разработку?").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi { assertEquals("С чего начать Android-разработку?", heading()) }
        } finally { onUi { scene.close() } }
    }

    @Test fun sourceGroupsCollapseIndependentlyAndKeepFileActionsAvailable() {
        val picks = mutableListOf<io.aequicor.magicpaper.domain.ResearchResourceScope>()
        val scene = onUi { ImageComposeScene(1280, 850) { ResearchWorkspacePreview(onPickFiles = { picks += it }) } }
        var frame = 0L
        fun render() { repeat(12) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        fun source(label: String) = scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty().contains("Использовать источник: $label") }
        fun click(label: String) = onUi { scene.action(label).config[SemanticsActions.OnClick].action!!.invoke() }
        try {
            render()
            click("Свернуть: Общие для чата"); render()
            onUi { assertFalse(source("План обучения.pdf")); assertTrue(source("Jetpack Compose")) }
            click("Добавить файлы: Общие для чата"); render()
            onUi {
                assertFalse(source("План обучения.pdf"), "Adding a file must not toggle the collapsed group")
                assertEquals(listOf(io.aequicor.magicpaper.domain.ResearchResourceScope.SHARED), picks)
            }
            click("Свернуть: Только этот вопрос"); render()
            onUi { assertFalse(source("Jetpack Compose")) }
            scene.capture("collapsed-source-groups", ++frame * 32_000_000L)
            click("Скрыть источники"); render()
            click("Развернуть источники"); render()
            onUi { assertFalse(source("План обучения.pdf")); assertFalse(source("Jetpack Compose")) }
            click("Развернуть: Общие для чата"); render()
            onUi { assertTrue(source("План обучения.pdf")); assertFalse(source("Jetpack Compose")) }
            click("Развернуть: Только этот вопрос"); render()
            onUi {
                assertTrue(source("Jetpack Compose"))
                val check = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty().contains("Использовать источник: Guide to app architecture") }
                assertEquals(androidx.compose.ui.state.ToggleableState.Off, check.config[SemanticsProperties.ToggleableState],
                    "Collapsing must preserve source selection")
            }
        } finally { onUi { scene.close() } }
    }

    @Test fun emptyAndShortDraftsExpandWithoutReplacingTheEditor() {
        for (draft in listOf("", "Короткий вопрос")) {
            val scene = onUi { ImageComposeScene(720, 650) { ResearchWorkspacePreview() } }
            var frame = 0L
            fun render() { repeat(12) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
            fun input() = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
            try {
                render()
                onUi { input().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(draft)) }
                render()
                val before = onUi { input().id to input().boundsInRoot.height }
                onUi { scene.action("Развернуть поле ввода").config[SemanticsActions.OnClick].action!!.invoke() }
                render()
                scene.capture(if (draft.isEmpty()) "expanded-empty" else "expanded-short", ++frame * 32_000_000L)
                onUi {
                    assertEquals(before.first, input().id)
                    assertEquals(draft, input().config[SemanticsProperties.EditableText].text)
                    assertTrue(input().boundsInRoot.height >= 180f, "Even an empty editor must visibly expand: ${input().boundsInRoot}")
                    assertTrue(input().boundsInRoot.height > before.second + 100f)
                    assertTrue(scene.action("Отправить").boundsInRoot.bottom <= 650)
                }
                scene.capture(if (draft.isEmpty()) "expanded-empty" else "expanded-short", ++frame * 32_000_000L)
                onUi { scene.action("Свернуть поле ввода").config[SemanticsActions.OnClick].action!!.invoke() }
                render()
                onUi { assertEquals(before.second, input().boundsInRoot.height, 1f) }
            } finally { onUi { scene.close() } }
        }
    }

    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.text(label: String) = nodes().first {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label }
    }
    private fun ImageComposeScene.action(label: String) = nodes().first { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label } ||
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
                node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } })
    }
    private fun ImageComposeScene.capture(name: String, frame: Long) = onUi {
        val directory = File("build/reports/research-workspace").apply { mkdirs() }
        File(directory, "$name.png").writeBytes(render(frame).use { image -> image.encodeToData()!!.use { it.bytes } })
    }

    @Test fun workspaceUsesOnlyPlatformSaveableLazyListKeys() {
        fun platformSaveable(value: Any?): Boolean = when (value) {
            null, is String, is Boolean, is Number, is Char -> true
            is MutableState<*> -> platformSaveable(value.value)
            is List<*> -> value.all(::platformSaveable)
            is Array<*> -> value.all(::platformSaveable)
            is Map<*, *> -> value.all { (key, item) -> platformSaveable(key) && platformSaveable(item) }
            else -> false
        }
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = ::platformSaveable)
        val scene = onUi { ImageComposeScene(1280, 850) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                ResearchWorkspacePreview()
            }
        } }
        try {
            repeat(3) { onUi { scene.render(it * 32_000_000L).close() } }
            onUi { registry.performSave() }
        } finally { onUi { scene.close() } }
    }

    @Test fun galleryKeepsReadingAndActionsVisibleAtNarrowAndLargeTextSizes() {
        data class Case(val name: String, val width: Int, val height: Int, val scale: Float = 1f)
        for (case in listOf(Case("reading", 1280, 850), Case("empty", 1280, 850),
            Case("narrow", 390, 780), Case("empty-narrow", 390, 780), Case("large-text", 720, 1000, 2f),
            Case("working", 1280, 850), Case("error", 390, 780), Case("unreadable-source", 1280, 850))) {
            val empty = case.name.startsWith("empty")
            val scene = onUi { ImageComposeScene(case.width, case.height) {
                CompositionLocalProvider(LocalDensity provides Density(1f, case.scale)) {
                    ResearchWorkspacePreview(empty, busy = case.name == "working", failed = case.name == "error",
                        unreadableSource = case.name == "unreadable-source")
                }
            } }
            try {
                repeat(24) { onUi { scene.render(it * 32_000_000L).close() }; Thread.sleep(5) }
                onUi {
                    for (label in listOf(if (case.name == "working") "Пауза" else "Отправить")) {
                        val bounds = scene.action(label).boundsInRoot
                        assertTrue(bounds.left >= 0 && bounds.right <= case.width, "$label clipped in ${case.name}")
                        assertTrue(bounds.top >= 0 && bounds.bottom <= case.height, "$label outside ${case.name}")
                    }
                    assertFalse(scene.nodes().any {
                        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Создать ветку" }
                    }, "Branch creation belongs in the chat menu, leaving the page clear")
                    if (case.width >= 1000) {
                        assertTrue(scene.text("Вопросы").boundsInRoot.left >= 0)
                        assertTrue(scene.text("Источники").boundsInRoot.right <= case.width)
                        assertTrue(scene.text("Общие для чата").boundsInRoot.width > 0)
                        if (case.name == "unreadable-source") {
                            val problem = scene.text("CAPTCHA")
                            assertTrue(problem.boundsInRoot.right <= case.width)
                            assertTrue(problem.boundsInRoot.bottom < scene.text("План обучения.pdf").boundsInRoot.top)
                        }
                        if (!empty) {
                            assertTrue(scene.text("Источники ответа").boundsInRoot.width > 0)
                            assertTrue(scene.text("developer.android.com").boundsInRoot.width > 0)
                            assertTrue(scene.text("PDF").boundsInRoot.top > scene.text("План обучения.pdf").boundsInRoot.bottom)
                        }
                    } else {
                        for (label in listOf("Развернуть вопросы", "Развернуть источники")) {
                            val bounds = scene.action(label).boundsInRoot
                            assertTrue(bounds.left >= 0 && bounds.right <= case.width, "$label clipped in ${case.name}")
                        }
                    }
                    val input = scene.nodes().first { node -> node.config.contains(SemanticsActions.SetText) &&
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(if (empty) "Сформулируйте" else "Уточните") } }
                    if (empty) {
                        assertTrue(input.boundsInRoot.center.y in (case.height * .3f)..(case.height * .75f))
                        assertTrue(scene.text("Что будем исследовать?").boundsInRoot.bottom < input.boundsInRoot.top)
                    }
                    if (case.width >= 1000) assertTrue(input.boundsInRoot.right < scene.text("Источники").boundsInRoot.left)
                }
                scene.capture(case.name, 900_000_000L)
            } finally { onUi { scene.close() } }
        }
    }

    @Test fun collapsedPanelsMoveTogglesIntoTitleAndScopedFileActionsStayReachable() {
        var newQuestions = 0
        val filePicks = mutableListOf<io.aequicor.magicpaper.domain.ResearchResourceScope>()
        val scene = onUi { ImageComposeScene(1280, 850) {
            ResearchWorkspacePreview(
                onNewQuestion = { newQuestions++ },
                onPickFiles = { filePicks += it },
            )
        } }
        var frame = 0L
        fun render() {
            repeat(12) {
                onUi { scene.render(++frame * 32_000_000L).close() }
                Thread.sleep(5)
            }
        }
        try {
            render()
            onUi {
                scene.action("Скрыть вопросы").config[SemanticsActions.OnClick].action!!.invoke()
                scene.action("Скрыть источники").config[SemanticsActions.OnClick].action!!.invoke()
            }
            render()
            onUi {
                assertTrue(scene.action("Развернуть вопросы").boundsInRoot.width > 0)
                assertTrue(scene.action("Развернуть источники").boundsInRoot.width > 0)
                val title = scene.nodes().first { it.config.contains(SemanticsProperties.Heading) }.boundsInRoot
                assertTrue(scene.action("Развернуть вопросы").boundsInRoot.right <= title.left)
                assertTrue(scene.action("Развернуть источники").boundsInRoot.left >= title.right)
                assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Вопросы" } })
            }
            scene.capture("collapsed-panels", ++frame * 32_000_000L)
            onUi { scene.action("Развернуть вопросы").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi {
                scene.action("Новый вопрос").config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(1, newQuestions)
                scene.action("Развернуть источники").config[SemanticsActions.OnClick].action!!.invoke()
            }
            render()
            onUi {
                scene.action("Добавить файлы: Общие для чата").config[SemanticsActions.OnClick].action!!.invoke()
                scene.action("Добавить файлы: Только этот вопрос").config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(io.aequicor.magicpaper.domain.ResearchResourceScope.entries.toList(), filePicks)
            }
        } finally { onUi { scene.close() } }
    }

    @Test fun sidePanelWidthsAreAdjustableThroughAccessibleSemantics() {
        val scene = onUi { ImageComposeScene(1280, 850) { ResearchWorkspacePreview() } }
        fun handle(label: String) = scene.nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
        }
        try {
            repeat(4) { onUi { scene.render(it * 32_000_000L).close() } }
            onUi {
                assertTrue(handle("Изменить ширину панели вопросов")
                    .config[SemanticsActions.SetProgress].action!!.invoke(260f))
                assertTrue(handle("Изменить ширину панели источников")
                    .config[SemanticsActions.SetProgress].action!!.invoke(320f))
            }
            repeat(4) { onUi { scene.render((it + 4) * 32_000_000L).close() } }
            onUi {
                assertEquals(260f, handle("Изменить ширину панели вопросов")
                    .config[SemanticsProperties.ProgressBarRangeInfo].current)
                assertEquals(320f, handle("Изменить ширину панели источников")
                    .config[SemanticsProperties.ProgressBarRangeInfo].current)
            }
            scene.capture("resized-panels", 300_000_000L)
        } finally { onUi { scene.close() } }
    }

    @Test fun sourceSearchReportsEmptyResultsAndClosingPreservesComposerDraft() {
        val scene = onUi { ImageComposeScene(720, 850) { ResearchEmptyPreview() } }
        var frame = 0L
        fun render() { repeat(16) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        try {
            render()
            onUi {
                scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                    .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Мой исследовательский вопрос"))
                scene.action("Развернуть источники").config[SemanticsActions.OnClick].action!!.invoke()
            }
            render()
            onUi { scene.action("Найти ещё").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi {
                scene.nodes().last { it.config.contains(SemanticsActions.SetText) }
                    .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Материалы исследования"))
            }
            render()
            onUi { scene.action("Найти").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi { assertTrue(scene.text("Ничего не найдено. Измените запрос.").boundsInRoot.height > 0) }
            scene.capture("source-validation", ++frame * 32_000_000L)
            onUi { scene.action("Закрыть поиск источников").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi { scene.action("Скрыть источники").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi {
                val input = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                assertEquals("Мой исследовательский вопрос", input.config[SemanticsProperties.EditableText].text)
                assertFalse(scene.action("Отправить").config.contains(SemanticsProperties.Disabled))
            }
            scene.capture("ready-to-send", ++frame * 32_000_000L)
        } finally { onUi { scene.close() } }
    }
    @Test fun expandingComposerKeepsTextAndFooterControlsOnSameRow() {
        val scene = onUi { ImageComposeScene(1280, 850) { ResearchWorkspacePreview() } }
        var frame = 0L
        fun render() { repeat(12) { onUi { scene.render(++frame * 32_000_000L).close() }; Thread.sleep(5) } }
        try {
            render()
            val draft = "Первая строка\nВторая строка\nТретья строка\nЧетвёртая строка\nПятая строка"
            onUi { scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(draft)) }
            render()
            val before = onUi { scene.nodes().first { it.config.contains(SemanticsActions.SetText) }.boundsInRoot.height }
            onUi { scene.action("Развернуть поле ввода").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi {
                val input = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                assertEquals(draft, input.config[SemanticsProperties.EditableText].text)
                assertTrue(input.boundsInRoot.height > before)
                val send = scene.action("Отправить").boundsInRoot
                val context = scene.action("Заполненность контекста: 24%").boundsInRoot
                assertEquals(send.center.y, context.center.y, 1f)
                assertTrue(context.right <= send.left)
            }
            scene.capture("expanded-composer", ++frame * 32_000_000L)
            onUi { scene.action("Свернуть поле ввода").config[SemanticsActions.OnClick].action!!.invoke() }
            render()
            onUi { assertEquals(draft, scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
                .config[SemanticsProperties.EditableText].text) }
        } finally { onUi { scene.close() } }
    }

}
